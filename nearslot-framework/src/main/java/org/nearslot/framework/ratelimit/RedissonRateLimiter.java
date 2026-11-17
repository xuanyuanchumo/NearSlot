package org.nearslot.framework.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流（ADR-016：Redisson RRateLimiter 令牌桶）。
 *
 * **自愈设计**（实测踩坑）：Redisson 的限流配置本身存在 Redis 里，
 * Redis 被 FLUSHALL 或数据丢失后，tryAcquire 会抛
 * "RateLimiter is not initialized"，导致接口 500。
 * 此处捕获该异常并**自动重建配置后重试一次**，避免运维操作（清缓存）
 * 直接把业务打挂——这是生产可用性的关键细节。
 */
@Slf4j
@Component
public class RedissonRateLimiter {

    /** 场景阈值登记：scene -> [permits, intervalSeconds]，用于自愈重建。 */
    private final Map<String, long[]> registry = new ConcurrentHashMap<>();

    private final RedissonClient redisson;

    public RedissonRateLimiter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 初始化（幂等）：每 intervalSeconds 秒允许 permits 次。 */
    public void init(String scene, long permits, long intervalSeconds) {
        registry.put(scene, new long[]{permits, intervalSeconds});
        RRateLimiter limiter = redisson.getRateLimiter(key(scene));
        limiter.trySetRate(RateType.OVERALL, permits, intervalSeconds, RateIntervalUnit.SECONDS);
    }

    /** 尝试获取许可；配置丢失时自动重建并重试一次。 */
    public boolean tryAcquire(String scene) {
        RRateLimiter limiter = redisson.getRateLimiter(key(scene));
        try {
            return limiter.tryAcquire(1);
        } catch (RuntimeException e) {
            if (isNotInitialized(e)) {
                log.warn("限流器配置丢失（可能 Redis 被清空），自动重建 scene={}", scene);
                rebuild(scene);
                try {
                    return redisson.getRateLimiter(key(scene)).tryAcquire(1);
                } catch (RuntimeException retry) {
                    // 重建仍失败：降级放行，避免把业务打挂（限流是保护手段，不是业务前提）
                    log.error("限流器重建失败，本次降级放行 scene={}", scene, retry);
                    return true;
                }
            }
            throw e;
        }
    }

    private void rebuild(String scene) {
        long[] cfg = registry.getOrDefault(scene, new long[]{60, 60});
        redisson.getRateLimiter(key(scene))
            .trySetRate(RateType.OVERALL, cfg[0], cfg[1], RateIntervalUnit.SECONDS);
    }

    private boolean isNotInitialized(RuntimeException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("not initialized");
    }

    private String key(String scene) {
        return "nearslot:rate:" + scene;
    }
}
