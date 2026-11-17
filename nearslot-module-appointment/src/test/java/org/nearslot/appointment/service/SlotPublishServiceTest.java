package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.StaffSkillQuery;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.nearslot.framework.mq.CacheInvalidationPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 时段发布单测（FR-2.2）。
 *
 * 重点覆盖本轮踩坑：预热余量键后绝不 invalidate 它（自毁缺陷回归）。
 */
class SlotPublishServiceTest {

    private TimeSlotMapper slotMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private DistributedLockTemplate lockTemplate;
    private CacheInvalidationPublisher publisher;
    private CacheManager cacheManager;
    private ReminderService reminderService;
    private StaffSkillQuery staffSkillQuery;
    private SlotPublishService service;

    private final LocalDateTime START = LocalDateTime.of(2026, 12, 1, 10, 0);
    private final LocalDateTime END = LocalDateTime.of(2026, 12, 1, 11, 0);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        slotMapper = mock(TimeSlotMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lockTemplate = mock(DistributedLockTemplate.class);
        when(lockTemplate.withWriteLock(any(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        publisher = mock(CacheInvalidationPublisher.class);
        when(publisher.publish(anyString())).thenReturn(true);
        cacheManager = mock(CacheManager.class);
        reminderService = mock(ReminderService.class);
        staffSkillQuery = mock(StaffSkillQuery.class);
        service = new SlotPublishService(slotMapper, redis, publisher, lockTemplate,
            reminderService, staffSkillQuery, cacheManager);
    }

    // ---------- 发布校验 ----------

    @Test
    @DisplayName("容量 <= 0 -> 拒绝")
    void rejectNonPositiveCapacity() {
        assertThrows(BaseException.class,
            () -> service.publish(1L, 2001L, null, START, END, 0, 0));
        verify(slotMapper, never()).insert(org.mockito.ArgumentMatchers.<TimeSlot>any());
    }

    @Test
    @DisplayName("结束时间不晚于开始 -> 拒绝")
    void rejectBadTimeRange() {
        assertThrows(BaseException.class,
            () -> service.publish(1L, 2001L, null, END, START, 1, 0));
        assertThrows(BaseException.class,
            () -> service.publish(1L, 2001L, null, START, START, 1, 0));
    }

    @Test
    @DisplayName("发布成功 -> 落库 + 预热余量 + 排期提醒")
    void publishHappyPath() {
        Long id = service.publish(1L, 2001L, 4001L, START, END, 3, 8);

        assertNotNull(id);
        verify(slotMapper).insert(org.mockito.ArgumentMatchers.<TimeSlot>any(TimeSlot.class));
        verify(valueOps).set(eq(RedisConstants.SLOT_STOCK + id), eq("3"), eq(24L), any());
        verify(reminderService).scheduleForSlot(id);
    }

    @Test
    @DisplayName("回归：预热余量键后绝不 invalidate 它（自毁缺陷）")
    void neverInvalidatesFreshStockKey() {
        Long id = service.publish(1L, 2001L, null, START, END, 2, 0);
        verify(cacheManager, never()).invalidate(RedisConstants.SLOT_STOCK + id);
    }

    @Test
    @DisplayName("MQ 可用 -> 只广播 shop 缓存，不降级")
    void broadcastOkNoFallback() {
        when(publisher.publish(anyString())).thenReturn(true);
        service.publish(1L, 2001L, null, START, END, 2, 0);
        verify(publisher).publish(RedisConstants.CACHE_SHOP + 1L);
        verify(cacheManager, never()).invalidate(anyString());
    }

    @Test
    @DisplayName("MQ 不可用 -> 降级本地失效 shop 缓存（仍不碰余量键）")
    void broadcastFailFallsBackLocally() {
        when(publisher.publish(anyString())).thenReturn(false);
        Long id = service.publish(1L, 2001L, null, START, END, 2, 0);
        verify(cacheManager).invalidate(RedisConstants.CACHE_SHOP + 1L);
        verify(cacheManager, never()).invalidate(RedisConstants.SLOT_STOCK + id);
    }

    @Test
    @DisplayName("minLevel 为 null -> 落 0")
    void nullMinLevelDefaultsToZero() {
        service.publish(1L, 2001L, null, START, END, 1, null);
        verify(slotMapper).insert(argThat((TimeSlot s) -> s.getMinLevel() != null && s.getMinLevel() == 0));
    }

    // ---------- 可约查询 ----------

    @Test
    @DisplayName("按服务项筛选：无服务者会该项目 -> 返回空")
    void filterByItemNoStaff() {
        when(staffSkillQuery.staffIdsByItem(4001L)).thenReturn(Set.of());
        assertTrue(service.listAvailable(1L, null, 4001L).isEmpty());
    }

    @Test
    @DisplayName("按服务项筛选：排除不会该项目的服务者时段")
    void filterExcludesUnskilled() {
        when(staffSkillQuery.staffIdsByItem(4001L)).thenReturn(Set.of(2001L));
        TimeSlot mine = new TimeSlot();
        mine.setId(1L); mine.setShopId(1L); mine.setServiceStaffId(2001L);
        mine.setStartsAt(START); mine.setEndsAt(END); mine.setCapacity(1); mine.setStatus(1);
        TimeSlot other = new TimeSlot();
        other.setId(2L); other.setShopId(1L); other.setServiceStaffId(2002L);
        other.setStartsAt(START); other.setEndsAt(END); other.setCapacity(1); other.setStatus(1);
        when(slotMapper.selectList(any())).thenReturn(List.of(mine, other));

        var out = service.listAvailable(1L, null, 4001L);
        assertEquals(1, out.size());
        assertEquals("1", out.get(0).get("id"));
    }

    @Test
    @DisplayName("余量回落：Redis 无键时用 capacity")
    void remainingFallsBackToCapacity() {
        TimeSlot s = new TimeSlot();
        s.setId(9L); s.setShopId(1L); s.setServiceStaffId(2001L);
        s.setStartsAt(START); s.setEndsAt(END); s.setCapacity(5); s.setStatus(1);
        when(slotMapper.selectList(any())).thenReturn(List.of(s));
        when(valueOps.get(anyString())).thenReturn(null);

        var out = service.listAvailable(1L, null, null);
        assertEquals(5, out.get(0).get("remaining"));
    }
}
