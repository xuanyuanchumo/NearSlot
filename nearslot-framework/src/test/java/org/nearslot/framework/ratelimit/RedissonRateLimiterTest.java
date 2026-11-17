package org.nearslot.framework.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 限流器单测（ADR-016：Redisson RRateLimiter 令牌桶）。
 *
 * <p>重点覆盖<b>自愈逻辑</b>（生产实测踩坑）：Redis 被 FLUSHALL 后 tryAcquire 抛
 * "RateLimiter is not initialized"，模板应自动重建配置并重试一次；
 * 重建仍失败时降级放行，绝不把业务打挂。</p>
 */
class RedissonRateLimiterTest {

    private static final String KEY = "nearslot:rate:order";
    private static final String NOT_INIT = "org.redisson.client.RedisException: RateLimiter is not initialized";

    private RedissonClient redisson;
    private RRateLimiter limiter;
    private RedissonRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        rateLimiter = new RedissonRateLimiter(redisson);
        stubbed(KEY);
    }

    /** 让 redisson.getRateLimiter(KEY) 返回 mock，并清掉 stubbing 期间的调用记录。 */
    private void stubbed(String key) {
        when(redisson.getRateLimiter(key)).thenReturn(limiter);
        clearInvocations(redisson, limiter);
    }

    // ---------------- init ----------------

    @Test
    @DisplayName("init -> 按 秒 为单位写入阈值，并登记 scene 供自愈重建使用")
    void initSetsRateInSeconds() {
        rateLimiter.init("order", 100, 1);

        verify(redisson).getRateLimiter(KEY);
        verify(limiter).trySetRate(RateType.OVERALL, 100L, 1L, RateIntervalUnit.SECONDS);
    }

    @Test
    @DisplayName("init 可重复调用（幂等重建），每次都会重新 trySetRate")
    void initIsRepeatable() {
        rateLimiter.init("order", 100, 1);
        rateLimiter.init("order", 200, 2);

        verify(limiter, times(2)).trySetRate(eq(RateType.OVERALL), anyLong(), anyLong(), eq(RateIntervalUnit.SECONDS));
        verify(limiter).trySetRate(RateType.OVERALL, 200L, 2L, RateIntervalUnit.SECONDS);
    }

    // ---------------- tryAcquire 正常路径 ----------------

    @Test
    @DisplayName("正常路径：拿到许可返回 true，不触发重建")
    void acquireOk() {
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertTrue(rateLimiter.tryAcquire("order"));
        verify(limiter).tryAcquire(1);
        verify(limiter, never()).trySetRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("正常路径：令牌耗尽返回 false（不重建、不降级放行）")
    void acquireDenied() {
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertFalse(rateLimiter.tryAcquire("order"), "限流命中必须如实返回 false，不能自愈成放行");
        verify(limiter, never()).trySetRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("未登记的 scene -> 使用 key 前缀 nearslot:rate:")
    void keyPrefix() {
        when(limiter.tryAcquire(1)).thenReturn(true);
        rateLimiter.tryAcquire("order");
        verify(redisson).getRateLimiter("nearslot:rate:order");
    }

    // ---------------- 自愈逻辑（核心） ----------------

    @Test
    @DisplayName("自愈核心：tryAcquire 抛 not initialized -> 自动重建并按登记阈值重试一次")
    void selfHealRebuildsAndRetriesOnce() {
        rateLimiter.init("order", 100, 5);
        // 实现中 init/rebuild/重试都走 redisson.getRateLimiter(key)，
        // 始终返回同一个 mock 才能观察到 trySetRate 与两次 tryAcquire。
        when(redisson.getRateLimiter(KEY)).thenReturn(limiter);
        when(limiter.tryAcquire(1))
            .thenThrow(new IllegalStateException(NOT_INIT))
            .thenReturn(true);
        clearInvocations(redisson, limiter);

        boolean allowed = rateLimiter.tryAcquire("order");

        assertTrue(allowed, "配置丢失重建后重试成功应放行");
        verify(limiter).trySetRate(RateType.OVERALL, 100L, 5L, RateIntervalUnit.SECONDS);
        verify(limiter, times(2)).tryAcquire(1);
    }

    @Test
    @DisplayName("自愈：重建仍失败 -> 降级放行 true（限流是保护手段，不能打挂业务）")
    void selfHealRetryFailureDegrades() {
        rateLimiter.init("order", 100, 5);
        when(limiter.tryAcquire(1))
            .thenThrow(new IllegalStateException(NOT_INIT))
            .thenThrow(new IllegalStateException("redis still down"));
        clearInvocations(redisson, limiter);

        assertTrue(rateLimiter.tryAcquire("order"), "重建失败必须降级放行，避免把整个接口打成 500");
        verify(limiter).trySetRate(RateType.OVERALL, 100L, 5L, RateIntervalUnit.SECONDS);
        verify(limiter, times(2)).tryAcquire(1);
    }

    @Test
    @DisplayName("自愈：重建后重试拿到 false -> 如实返回 false（不因重建而放行）")
    void selfHealRetryDenied() {
        rateLimiter.init("order", 10, 1);
        when(limiter.tryAcquire(1))
            .thenThrow(new IllegalStateException(NOT_INIT))
            .thenReturn(false);
        clearInvocations(redisson, limiter);

        assertFalse(rateLimiter.tryAcquire("order"), "重建后重试被拒，必须如实返回 false");
        verify(limiter).trySetRate(RateType.OVERALL, 10L, 1L, RateIntervalUnit.SECONDS);
    }

    @Test
    @DisplayName("自愈：未登记 scene 时按默认阈值 60/60 重建")
    void selfHealFallsBackToDefaultRate() {
        // 未 init 时 registry 为空 -> rebuild 回落默认 {60,60}
        when(redisson.getRateLimiter(KEY)).thenReturn(limiter);
        when(limiter.tryAcquire(1))
            .thenThrow(new IllegalStateException(NOT_INIT))
            .thenReturn(true);
        clearInvocations(redisson, limiter);

        assertTrue(rateLimiter.tryAcquire("order"));

        verify(limiter).trySetRate(RateType.OVERALL, 60L, 60L, RateIntervalUnit.SECONDS);
    }

    @Test
    @DisplayName("非 not-initialized 异常 -> 原样抛出，不做自愈（不掩盖真实故障）")
    void otherRuntimeExceptionPropagates() {
        when(limiter.tryAcquire(1)).thenThrow(new IllegalStateException("connection refused"));
        clearInvocations(redisson, limiter);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> rateLimiter.tryAcquire("order"));

        assertEquals("connection refused", e.getMessage());
        verify(limiter, never()).trySetRate(any(), anyLong(), anyLong(), any());
        verify(redisson, times(1)).getRateLimiter(KEY);
    }

    @Test
    @DisplayName("异常 message 为 null -> 视为非自愈异常并抛出（防 NPE）")
    void nullMessageRuntimeExceptionPropagates() {
        when(limiter.tryAcquire(1)).thenThrow(new IllegalArgumentException());
        clearInvocations(redisson, limiter);

        assertThrows(IllegalArgumentException.class, () -> rateLimiter.tryAcquire("order"));
        verify(limiter, never()).trySetRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("异常 message 不含 not initialized -> 仍抛出（关键字匹配是精确的）")
    void unrelatedMessageDoesNotHeal() {
        when(limiter.tryAcquire(1)).thenThrow(new IllegalStateException("RateLimiter is broken"));
        clearInvocations(redisson, limiter);

        assertThrows(IllegalStateException.class, () -> rateLimiter.tryAcquire("order"));
        verify(limiter, never()).trySetRate(any(), anyLong(), anyLong(), any());
    }
}
