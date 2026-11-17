package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候补等级过滤与提升重校验单测（FR-3.2 / research/09b §1）。
 *
 * 核心场景：候选入队后等级被下调，提升时必须重校验并跳过。
 */
class PromotionLevelFilterTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zsetOps;
    private SetOperations<String, String> setOps;
    private HashOperations<String, Object, Object> hashOps;
    private TimeSlotMapper slotMapper;
    private PromotionService promotion;
    private WaitlistService waitlist;
    private DistributedLockTemplate lockTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zsetOps = mock(ZSetOperations.class);
        setOps = mock(SetOperations.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForHash()).thenReturn(hashOps);

        slotMapper = mock(TimeSlotMapper.class);
        lockTemplate = mock(DistributedLockTemplate.class);
        when(lockTemplate.withLock(any(), anyLong(), anyLong(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
    }

    private TimeSlot slot(int minLevel) {
        TimeSlot s = new TimeSlot();
        s.setId(3001L);
        s.setMinLevel(minLevel);
        return s;
    }

    // ---------- 入队等级过滤 ----------

    @Test
    @DisplayName("等级不足 -> join 抛 LEVEL_NOT_MET，不写队列")
    void joinRejectsLowLevel() {
        when(slotMapper.selectById(3001L)).thenReturn(slot(8));
        waitlist = new WaitlistService(redis, slotMapper, uid -> 5);

        BaseException e = assertThrows(BaseException.class, () -> waitlist.join(3001L, 1001L));
        assertEquals(BaseCode.LEVEL_NOT_MET, e.getCode());
        verify(zsetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("等级满足 -> join 成功入队")
    void joinAcceptsSufficientLevel() {
        when(slotMapper.selectById(3001L)).thenReturn(slot(3));
        when(setOps.add(anyString(), anyString())).thenReturn(1L);
        when(hashOps.get(anyString(), any())).thenReturn(null);
        when(zsetOps.rank(anyString(), anyString())).thenReturn(0L);
        waitlist = new WaitlistService(redis, slotMapper, uid -> 5);

        assertTrue(waitlist.join(3001L, 1001L) > 0);
        verify(zsetOps).add(eq(RedisConstants.SUBSCRIBE_ZSET + 3001L), eq("1001"), anyDouble());
    }

    // ---------- 提升重校验 ----------

    @Test
    @DisplayName("队首等级不足 -> 跳过并继续下一位（不提升不合规用户）")
    void promoteSkipsIneligibleCandidate() {
        when(slotMapper.selectById(3001L)).thenReturn(slot(8));

        // 队首是低等级用户 1001，第二位是合格用户 1002
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(java.util.List.of("1001")))
            .thenReturn(new LinkedHashSet<>(java.util.List.of("1002")));

        // 1001 -> Lv3（不足），1002 -> Lv9（合格）
        UserLevelQuery levelQuery = uid -> uid == 1001L ? 3 : 9;
        promotion = new PromotionService(redis, lockTemplate, slotMapper, levelQuery);

        Long promoted = promotion.promoteNext(3001L);

        assertEquals(1002L, promoted, "应跳过不合格的 1001，提升 1002");
        verify(zsetOps).remove(anyString(), eq("1001"));  // 1001 被出队
        verify(hashOps).put(eq(RedisConstants.SUBSCRIBE_STATUS + 3001L),
            eq("1002"), eq(String.valueOf(WaitlistService.STATUS_SUCCESS)));
    }

    @Test
    @DisplayName("全部候选都不合格 -> 返回 null 且队列被清空")
    void promoteReturnsNullWhenAllIneligible() {
        when(slotMapper.selectById(3001L)).thenReturn(slot(9));
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(java.util.List.of("1001")))
            .thenReturn(new LinkedHashSet<>());
        promotion = new PromotionService(redis, lockTemplate, slotMapper, uid -> 1);

        assertNull(promotion.promoteNext(3001L));
        verify(zsetOps).remove(anyString(), eq("1001"));
    }

    @Test
    @DisplayName("无等级门槛 -> 直接提升队首")
    void promoteWithoutMinLevel() {
        when(slotMapper.selectById(3001L)).thenReturn(slot(0));
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(java.util.List.of("1001")));
        promotion = new PromotionService(redis, lockTemplate, slotMapper, uid -> 1);

        assertEquals(1001L, promotion.promoteNext(3001L));
    }

    @Test
    @DisplayName("空队列 -> 返回 null")
    void promoteEmptyQueue() {
        when(slotMapper.selectById(3001L)).thenReturn(slot(0));
        when(zsetOps.range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());
        promotion = new PromotionService(redis, lockTemplate, slotMapper, uid -> 10);

        assertNull(promotion.promoteNext(3001L));
    }
}
