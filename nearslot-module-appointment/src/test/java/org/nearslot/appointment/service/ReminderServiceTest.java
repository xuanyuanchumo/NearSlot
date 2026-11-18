package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 开抢提醒单测（FR-3.4）。
 * 覆盖：延迟排期、临近开始立即触发、去重窗口、无候补用户。
 */
class ReminderServiceTest {

    private TimeSlotMapper slotMapper;
    private RedissonDelayQueue delayQueue;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ZSetOperations<String, String> zsetOps;
    private ReminderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        slotMapper = mock(TimeSlotMapper.class);
        delayQueue = mock(RedissonDelayQueue.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        service = new ReminderService(slotMapper, delayQueue, redis, new org.nearslot.appointment.config.AppointmentProperties());
    }

    private TimeSlot slot(LocalDateTime start) {
        TimeSlot s = new TimeSlot();
        s.setId(3001L);
        s.setStartsAt(start);
        return s;
    }

    @Test
    @DisplayName("距开始 >2min -> 投递延迟消息，delay = 距开始 - 120s")
    void schedulesDelayWhenFarEnough() {
        LocalDateTime start = LocalDateTime.now().plusMinutes(10);
        when(slotMapper.selectById(3001L)).thenReturn(slot(start));
        when(zsetOps.range(anyString(), anyLong(), anyLong())).thenReturn(Set.of());

        service.scheduleForSlot(3001L);

        verify(delayQueue).offer(eq(ReminderService.REMINDER_QUEUE), eq("3001"), anyLong());
        verify(zsetOps, never()).range(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("距开始 <2min -> 立即触发提醒（不丢弃）")
    void firesImmediatelyWhenTooClose() {
        LocalDateTime start = LocalDateTime.now().plusSeconds(60);
        when(slotMapper.selectById(3001L)).thenReturn(slot(start));
        when(zsetOps.range(anyString(), anyLong(), anyLong())).thenReturn(Set.of("1001"));

        service.scheduleForSlot(3001L);

        verify(delayQueue, never()).offer(anyString(), any(), anyLong());
        verify(zsetOps).range(RedisConstants.SUBSCRIBE_ZSET + 3001L, 0, -1);
    }

    @Test
    @DisplayName("去重窗口：同一用户只提醒一次")
    void dedupWithinWindow() {
        when(zsetOps.range(anyString(), anyLong(), anyLong()))
            .thenReturn(new LinkedHashSet<>(java.util.List.of("1001", "1002")));
        when(valueOps.setIfAbsent(anyString(), eq("1"), anyLong(), any()))
            .thenReturn(true)   // 1001 首次
            .thenReturn(false); // 1002 已被提醒过

        service.fireReminder(3001L);

        verify(valueOps, times(2)).setIfAbsent(anyString(), eq("1"), eq(300L), any());
    }

    @Test
    @DisplayName("无候补用户 -> 不发送任何提醒")
    void noCandidates_noop() {
        when(zsetOps.range(anyString(), anyLong(), anyLong()))
            .thenReturn(java.util.Set.of());

        service.fireReminder(3001L);

        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), anyLong(), any());
    }
}
