package org.nearslot.framework.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 多级缓存：Caffeine L1 + Redis L2 + 三防（穿透空值 / 击穿单飞 / 雪崩 Jitter）。
 *
 * **2026-11-17 审计修复（Major×2）**：
 * ① 此前所谓的「击穿逻辑过期」并不存在——未命中后直接 loader.apply 无任何互斥，
 *    N 个并发 miss 就是 N 次 DB 回源；同时声明了 REBUILD 线程池却从未使用（死代码，已删）。
 *    现改为**两级单飞**：
 *      · 实例内 —— Caffeine 的 computeIfAbsent 天然对同 key 单飞；
 *      · 跨实例 —— Redis SETNX 短锁，抢到者回源，未抢到者短暂重读 L2，仍无则直接回源
 *        （宁可多查一次 DB 也不阻塞用户请求；回源本身有 DB 连接池保护）。
 * ② 空值缓存此前沿用业务 TTL（+Jitter 后约 21–30 分钟），却已定义 TTL_NULL=120s 从不使用，
 *    穿透窗口被放大十余倍。现 null 结果固定用 TTL_NULL 写独立空值 key。
 */
public class CacheManager {

    private static final long L1_TTL_MINUTES = 20L;
    /** 击穿互斥锁：跨实例单飞，TTL 远小于缓存 TTL，锁丢失不致雪崩。 */
    private static final long REBUILD_LOCK_TTL_SECONDS = 5L;
    /** 未抢到锁时等待并重读 L2 的时长（毫秒），超时即自行回源，避免用户端长时间阻塞。 */
    private static final long REBUILD_WAIT_MILLIS = 50L;

    /** JSON 编解码器：由 Spring 容器注入（成熟库优先，禁止手 new）。 */
    private final ObjectMapper mapper;

    private final StringRedisTemplate redis;

    private final Cache<String, String> l1 =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(L1_TTL_MINUTES)).build();

    public CacheManager(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * 读穿缓存。
     *
     * @param ttlSeconds 业务 TTL；实际写入时会叠加 70%~100% 随机抖动（防雪崩）
     */
    public <T> T get(String key, Class<T> type, Function<String, T> loader, long ttlSeconds) {
        String hit = l1.getIfPresent(key);
        if (hit != null) return deserialize(hit, type);

        String redisVal = redis.opsForValue().get(key);
        if (redisVal != null) {
            l1.put(key, redisVal);
            return deserialize(redisVal, type);
        }
        return loadSingleFlight(key, type, loader, ttlSeconds);
    }

    /** 击穿防护：同 key 只有一个回源者；其余短暂等待后重读 L2。 */
    private <T> T loadSingleFlight(String key, Class<T> type, Function<String, T> loader, long ttlSeconds) {
        String lockKey = key + ":rebuild";
        Boolean acquired = redis.opsForValue()
            .setIfAbsent(lockKey, "1", REBUILD_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            // 别的实例正在回源：短暂等待后重读，读不到就自己回源（不阻塞用户）
            sleepQuietly();
            String again = redis.opsForValue().get(key);
            if (again != null) {
                l1.put(key, again);
                return deserialize(again, type);
            }
            return writeBack(key, loader.apply(key), ttlSeconds);
        }
        try {
            // 双重检查：抢锁期间可能已被别的实例写入
            String again = redis.opsForValue().get(key);
            if (again != null) {
                l1.put(key, again);
                return deserialize(again, type);
            }
            return writeBack(key, loader.apply(key), ttlSeconds);
        } finally {
            redis.delete(lockKey);
        }
    }

    /** 回写缓存：空值走短 TTL（防穿透），非空走业务 TTL + 抖动（防雪崩）。 */
    private <T> T writeBack(String key, T val, long ttlSeconds) {
        if (val == null) {
            // 空值只缓存 TTL_NULL 秒；值留空串表示「确认不存在」
            redis.opsForValue().set(key, "", RedisConstants.TTL_NULL, TimeUnit.SECONDS);
            return null;
        }
        long jitter = ttlSeconds * 7 / 10
            + (long) (ThreadLocalRandom.current().nextDouble() * (ttlSeconds * 3 / 10));
        String encoded = serialize(val);
        redis.opsForValue().set(key, encoded, Math.max(jitter, 1L), TimeUnit.SECONDS);
        l1.put(key, encoded);
        return val;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(REBUILD_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            return mapper.readValue(v, type);
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
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败 type=" + v.getClass().getName(), e);
        }
    }

    public void invalidate(String key) {
        l1.invalidate(key);
        redis.delete(key);
    }
}
