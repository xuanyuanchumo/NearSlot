package org.nearslot.framework.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.framework.ratelimit.RedissonRateLimiter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 限流场景初始化单测。
 *
 * 为什么值得测：RRateLimiter 未 init 就 tryAcquire 会抛 "RateLimiter is not initialized"，
 * 实测曾让登录接口直接 500。这个 Runner 是唯一的注册入口，漏注册即全站登录故障。
 */
class RateLimitInitializerTest {

    @Test
    @DisplayName("启动注册 login/appointment/agent 三个场景阈值")
    void initializesAllScenes() {
        RedissonRateLimiter limiter = mock(RedissonRateLimiter.class);
        RateLimitInitializer initializer = new RateLimitInitializer(limiter);

        assertNotNull(initializer.rateLimitSceneInitializer());
        // 模拟 Spring 启动回调
        assertDoesNotThrow(() -> initializer.rateLimitSceneInitializer().run(null));

        verify(limiter).init("login", 20, 60);
        verify(limiter).init("appointment", 60, 60);
        verify(limiter).init("agent", 30, 60);
        verifyNoMoreInteractions(limiter);
    }
}
