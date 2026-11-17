package org.nearslot.framework.ratelimit;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 限流（ADR-016：Redisson RRateLimiter 令牌桶，分布式一致，免自研 Lua 边界处理）。
 * 场景化阈值由调用方按 scene 传入（登录/抢约/Agent 会话）。
 */
@Component
public class RedissonRateLimiter {
    private final RedissonClient redisson;
    public RedissonRateLimiter(RedissonClient redisson) { this.redisson = redisson; }

    /** 初始化（幂等）：每 interval 秒允许 permits 次。 */
    public void init(String scene, long permits, long intervalSeconds) {
        RRateLimiter limiter = redisson.getRateLimiter("nearslot:rate:" + scene);
        limiter.trySetRate(RateType.OVERALL, permits, intervalSeconds, RateIntervalUnit.SECONDS);
    }

    public boolean tryAcquire(String scene) {
        return redisson.getRateLimiter("nearslot:rate:" + scene).tryAcquire(1);
    }
}
