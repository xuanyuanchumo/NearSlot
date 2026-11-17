package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 余量预热单测。
 *
 * 回归背景：未预热时抢约会返回 10004「无库存键」，链路看似失败实为缺预热。
 */
class SlotStockPreloaderTest {

    private TimeSlotMapper slotMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SlotStockPreloader preloader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        slotMapper = mock(TimeSlotMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        preloader = new SlotStockPreloader(slotMapper, redis,
            mock(HoldTimeoutTask.class), mock(ReminderService.class),
            mock(PrepareReminderService.class));
    }

    private TimeSlot slot(long id, Integer capacity, Integer status) {
        TimeSlot s = new TimeSlot();
        s.setId(id); s.setCapacity(capacity); s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("单条预热：写入余量键，TTL 24h")
    void preloadWritesStock() {
        preloader.preload(slot(3001L, 5, 1));
        // preload 是两参 set（无 TTL），TTL 由调用方/发布流程控制
        verify(valueOps).set(eq(RedisConstants.SLOT_STOCK + 3001L), eq("5"));
    }

    @Test
    @DisplayName("capacity 为 null -> 回落 1（防 NPE）")
    void preloadNullCapacityFallsBack() {
        preloader.preload(slot(3002L, null, 1));
        verify(valueOps).set(eq(RedisConstants.SLOT_STOCK + 3002L), eq("1"));
    }

    /**
     * 启动预热必须用 SETNX（只补缺失键）。
     *
     * 2026-11-17 审计修复（Blocker）：原实现无条件 SET capacity，
     * 每次重启都把已售出的余量重置回满值 —— 直接超卖。
     */
    @Test
    @DisplayName("启动预热：只处理 status=1 的时段，且必须用 SETNX（不得覆盖在售余量）")
    void warmUpOnlyActiveSlotsUsesSetIfAbsent() throws Exception {
        when(slotMapper.selectList(isNull())).thenReturn(List.of(
            slot(1L, 2, 1), slot(2L, 3, 2), slot(3L, 4, 1)));
        when(valueOps.setIfAbsent(anyString(), anyString())).thenReturn(true);

        ApplicationRunner runner = preloader.slotStockWarmUp();
        runner.run(null);

        verify(valueOps).setIfAbsent(eq(RedisConstants.SLOT_STOCK + 1L), eq("2"));
        verify(valueOps).setIfAbsent(eq(RedisConstants.SLOT_STOCK + 3L), eq("4"));
        verify(valueOps, never()).setIfAbsent(eq(RedisConstants.SLOT_STOCK + 2L), anyString());
        // 关键：启动预热绝不能用无条件 set（否则重启即超卖）
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("启动预热：DB 异常不影响启动（catch 后只告警）")
    void warmUpSwallowsException() {
        when(slotMapper.selectList(isNull())).thenThrow(new RuntimeException("db down"));
        ApplicationRunner runner = preloader.slotStockWarmUp();
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    @DisplayName("启动预热：status 为 null 的时段被跳过")
    void warmUpSkipsNullStatus() throws Exception {
        when(slotMapper.selectList(isNull())).thenReturn(List.of(slot(9L, 1, null)));
        preloader.slotStockWarmUp().run(null);
        verify(valueOps, never()).set(anyString(), anyString());
    }
}
