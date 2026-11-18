package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
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
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候补队列与提升单测（FR-3.2 / research/09b §1）。
 *
 * 核心场景：
 * 1. 入队时的等级过滤；
 * 2. 提升时的等级**重校验**（候选可能是在等级变更前入队的）；
 * 3. **提升必须真的建单**——2026-11-17 审计发现原实现只改 Redis 三键、
 *    不建订单不扣库存，被"提升"的用户在「我的预约」里什么都看不到，
 *    余量也没被真正占用。现改为走 {@link SlotBookingCore#book}，本测试锁住该行为。
 */
class PromotionLevelFilterTest {

    private static final long SLOT_ID = 3001L;

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zsetOps;
    private SetOperations<String, String> setOps;
    private HashOperations<String, Object, Object> hashOps;
    private TimeSlotMapper slotMapper;
    private AppointmentOrderMapper orderMapper;
    private SlotBookingCore bookingCore;
    private DistributedLockTemplate lockTemplate;
    private PromotionService promotion;
    private WaitlistService waitlist;

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
        orderMapper = mock(AppointmentOrderMapper.class);
        bookingCore = mock(SlotBookingCore.class);
        lockTemplate = mock(DistributedLockTemplate.class);
        when(lockTemplate.withLock(any(), anyLong(), anyLong(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());
        when(bookingCore.holdSeconds()).thenReturn(600L);
    }

    private TimeSlot slot(int minLevel) {
        TimeSlot s = new TimeSlot();
        s.setId(SLOT_ID);
        s.setMinLevel(minLevel);
        s.setCapacity(1);
        return s;
    }

    /** 构造 PromotionService（构造器顺序严格对齐 @RequiredArgsConstructor）。 */
    private PromotionService promotionWith(UserLevelQuery levelQuery) {
        return new PromotionService(redis, lockTemplate, slotMapper, levelQuery, bookingCore,
            new org.nearslot.appointment.config.AppointmentProperties());
    }

    // ---------- 入队等级过滤 ----------

    @Test
    @DisplayName("等级不足 -> join 抛 LEVEL_NOT_MET，不写队列")
    void joinRejectsLowLevel() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(8));
        waitlist = new WaitlistService(redis, slotMapper, uid -> 5);

        BaseException e = assertThrows(BaseException.class, () -> waitlist.join(SLOT_ID, 1001L));

        assertEquals(BaseCode.LEVEL_NOT_MET, e.getCode());
        verify(zsetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("等级满足 -> join 成功入队")
    void joinAcceptsSufficientLevel() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(3));
        when(setOps.add(anyString(), anyString())).thenReturn(1L);
        when(hashOps.get(anyString(), any())).thenReturn(null);
        when(zsetOps.rank(anyString(), anyString())).thenReturn(0L);
        waitlist = new WaitlistService(redis, slotMapper, uid -> 5);

        assertTrue(waitlist.join(SLOT_ID, 1001L) > 0);
        verify(zsetOps).add(eq(RedisConstants.SUBSCRIBE_ZSET + SLOT_ID), eq("1001"), anyDouble());
    }

    // ---------- 提升重校验 ----------

    @Test
    @DisplayName("队首等级不足 -> 跳过并继续下一位（不提升不合规用户）")
    void promoteSkipsIneligibleCandidate() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(8));
        when(bookingCore.book(1002L, SLOT_ID)).thenReturn(9001L);

        // 队首是低等级用户 1001，第二位是合格用户 1002
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(List.of("1001")))
            .thenReturn(new LinkedHashSet<>(List.of("1002")));

        UserLevelQuery levelQuery = uid -> uid == 1001L ? 3 : 9;
        promotion = promotionWith(levelQuery);

        Long promoted = promotion.promoteNext(SLOT_ID);

        assertEquals(1002L, promoted, "应跳过不合格的 1001，提升 1002");
        verify(zsetOps).remove(anyString(), eq("1001"));  // 1001 被出队
        verify(hashOps).put(eq(RedisConstants.SUBSCRIBE_STATUS + SLOT_ID),
            eq("1002"), eq(String.valueOf(WaitlistService.STATUS_SUCCESS)));
    }

    @Test
    @DisplayName("全部候选都不合格 -> 返回 null 且队列被清空")
    void promoteReturnsNullWhenAllIneligible() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(9));
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(List.of("1001")))
            .thenReturn(new LinkedHashSet<>());
        promotion = promotionWith(uid -> 1);

        assertNull(promotion.promoteNext(SLOT_ID));
        verify(zsetOps).remove(anyString(), eq("1001"));
    }

    @Test
    @DisplayName("无等级门槛 -> 直接提升队首，且必须真的建单（不留空提升）")
    void promoteWithoutMinLevelCreatesOrder() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(0));
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(List.of("1001")));
        when(bookingCore.book(1001L, SLOT_ID)).thenReturn(9001L);
        promotion = promotionWith(uid -> 1);

        assertEquals(1001L, promotion.promoteNext(SLOT_ID));

        // 关键断言：提升 = 真实抢约（预扣 + 落单），不是只改 Redis 状态。
        // Hold 超时排期由 SlotBookingCore 内部完成（它持有 HoldTimeoutTask），
        // 这里只验证内核被真正调用。
        verify(bookingCore).book(1001L, SLOT_ID);
        verify(hashOps).put(eq(RedisConstants.SUBSCRIBE_STATUS + SLOT_ID),
            eq("1001"), eq(String.valueOf(WaitlistService.STATUS_SUCCESS)));
    }

    @Test
    @DisplayName("提升时余量已被抢走（book 抛业务异常）-> 跳过该候选、不标记 SUCCESS")
    void promoteSkipsWhenBookingFails() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(0));
        when(zsetOps.range(anyString(), eq(0L), eq(0L)))
            .thenReturn(new LinkedHashSet<>(List.of("1001")))
            .thenReturn(new LinkedHashSet<>(List.of("1002")));
        when(bookingCore.book(1001L, SLOT_ID)).thenThrow(new BaseException(BaseCode.SLOT_NO_STOCK));
        when(bookingCore.book(1002L, SLOT_ID)).thenReturn(9002L);
        promotion = promotionWith(uid -> 1);

        assertEquals(1002L, promotion.promoteNext(SLOT_ID));

        // 失败的候选不能被标成 SUCCESS（否则用户以为约到了，实际没有订单）
        verify(hashOps, never()).put(anyString(), eq("1001"), eq(String.valueOf(WaitlistService.STATUS_SUCCESS)));
        verify(hashOps).put(anyString(), eq("1002"), eq(String.valueOf(WaitlistService.STATUS_SUCCESS)));
    }

    @Test
    @DisplayName("空队列 -> 返回 null")
    void promoteEmptyQueue() {
        when(slotMapper.selectById(SLOT_ID)).thenReturn(slot(0));
        when(zsetOps.range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());
        promotion = promotionWith(uid -> 10);

        assertNull(promotion.promoteNext(SLOT_ID));
    }
}
