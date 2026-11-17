package org.nearslot.framework.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.ratelimit.RedissonRateLimiter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 限流场景初始化（启动时幂等注册）。
 *
 * 实测踩坑：Redisson RRateLimiter 未 init 即 tryAcquire 会抛
 * "RateLimiter is not initialized" 并使接口 500。启动时必须先注册场景阈值。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RateLimitInitializer {

    private final RedissonRateLimiter rateLimiter;

    @Bean
    public ApplicationRunner rateLimitSceneInitializer() {
        return args -> {
            // 登录：每 60 秒 20 次（防爆破的第一道闸）
            rateLimiter.init("login", 20, 60);
            // 抢约：每 60 秒 60 次
            rateLimiter.init("appointment", 60, 60);
            // Agent 会话：每 60 秒 30 次（security/guidelines.md §2）
            rateLimiter.init("agent", 30, 60);
            log.info("限流场景已初始化: login/appointment/agent");
        };
    }
}
