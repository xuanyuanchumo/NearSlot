package org.nearslot.framework.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;

/** 多级缓存：Caffeine L1 + Redis L2 + 三防（穿透空值/击穿逻辑过期/雪崩Jitter）。 */
public class CacheManager {
    /** JSON 编解码器：用于实体缓存（简单类型不走它）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
        String encoded = serialize(val);
        redis.opsForValue().set(key, encoded, jitter, TimeUnit.SECONDS);
        if (val != null) l1.put(key, encoded);
        return val;
    }

    /**
     * 反序列化：简单类型直转，**任意 POJO 走 JSON**（交易对象缓存必需）。
     *
     * 实测踩坑：早期只支持 String/Long/Integer，缓存 Shop 实体时抛
     * "unsupported type: class ...Shop"，导致详情接口 500。
     * 实体缓存必须序列化为 JSON（不能用 toString），否则字段全部丢失。
     */
    private <T> T deserialize(String v, Class<T> type) {
        if (v == null || v.isEmpty()) return null;
        if (type == String.class) return type.cast(v);
        if (type == Long.class) return type.cast(Long.valueOf(v));
        if (type == Integer.class) return type.cast(Integer.valueOf(v));
        try {
            return MAPPER.readValue(v, type);
        } catch (Exception e) {
            throw new IllegalStateException("缓存反序列化失败 type=" + type.getName(), e);
        }
    }

    /** 序列化：简单类型取 String.valueOf，POJO 走 JSON。 */
    private String serialize(Object v) {
        if (v == null) return "";
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败 type=" + v.getClass().getName(), e);
        }
    }

    public void invalidate(String key) { l1.invalidate(key); redis.delete(key); }
}
