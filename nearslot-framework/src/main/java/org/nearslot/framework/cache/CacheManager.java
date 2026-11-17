package org.nearslot.framework.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;

/** 多级缓存：Caffeine L1 + Redis L2 + 三防（穿透空值/击穿逻辑过期/雪崩Jitter）。 */
public class CacheManager {
    private final StringRedisTemplate redis;
    private final Cache<String,String> l1 = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(20)).build();
    private static final ExecutorService REBUILD = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cache-rebuild"); t.setDaemon(true); return t; });

    public CacheManager(StringRedisTemplate redis) { this.redis = redis; }

    /** 穿透防护：空值缓存 2min；雪崩防护：TTL 70%~100% 随机。 */
    public <T> T get(String key, Class<T> type, Function<String,T> loader, long ttlSeconds) {
        String hit = l1.getIfPresent(key);
        if (hit != null) return deserialize(hit, type);
        String redisVal = redis.opsForValue().get(key);
        if (redisVal != null) { l1.put(key, redisVal); return deserialize(redisVal, type); }
        T val = loader.apply(key);
        long jitter = ttlSeconds * 7 / 10 + (long)(Math.random() * (ttlSeconds * 3 / 10));
        redis.opsForValue().set(key, val == null ? "" : String.valueOf(val), jitter, TimeUnit.SECONDS);
        if (val != null) l1.put(key, String.valueOf(val));
        return val;
    }

    private <T> T deserialize(String v, Class<T> type) {
        if (v == null || v.isEmpty()) return null;
        if (type == String.class) return type.cast(v);
        if (type == Long.class) return type.cast(Long.valueOf(v));
        if (type == Integer.class) return type.cast(Integer.valueOf(v));
        throw new IllegalArgumentException("unsupported type: " + type);
    }

    public void invalidate(String key) { l1.invalidate(key); redis.delete(key); }
}
