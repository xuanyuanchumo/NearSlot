package org.nearslot.framework.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 多级缓存单测：三防（穿透/击穿/雪崩）+ POJO 序列化 + L1 命中。 */
class CacheManagerTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private CacheManager cm;

    /** 测试用 POJO（验证 JSON 序列化路径）。 */
    public record Demo(long id, String name) {}

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        cm = new CacheManager(redis, new ObjectMapper(),
            new org.nearslot.framework.config.properties.FrameworkProperties());
    }

    @Test
    @DisplayName("L2 命中 -> 回填 L1，不调用 loader")
    void redisHitBackfillsL1() {
        when(valueOps.get("k")).thenReturn("v1");
        AtomicInteger calls = new AtomicInteger();
        assertEquals("v1", cm.get("k", String.class, key -> { calls.incrementAndGet(); return "x"; }, 60));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("L1 命中 -> 不再访问 Redis")
    void l1HitSkipsRedis() {
        when(valueOps.get("k2")).thenReturn("v2");
        cm.get("k2", String.class, k -> "x", 60);
        cm.get("k2", String.class, k -> "x", 60);
        verify(valueOps, times(1)).get("k2");
    }

    @Test
    @DisplayName("全未命中 -> 调 loader 并写 Redis（TTL 70%~100% 抖动）")
    void missLoadsAndWritesWithJitter() {
        when(valueOps.get("k3")).thenReturn(null);
        String v = cm.get("k3", String.class, k -> "loaded", 100);
        assertEquals("loaded", v);
        verify(valueOps).set(eq("k3"), eq("loaded"), longThat(t -> t >= 70 && t <= 100), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("穿透防护：loader 返回 null -> 缓存空串（2min 级 TTL 内不再穿透）")
    void nullCachesEmptyString() {
        when(valueOps.get("k4")).thenReturn(null);
        assertNull(cm.get("k4", String.class, k -> null, 60));
        verify(valueOps).set(eq("k4"), eq(""), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("POJO 走 JSON 序列化（回归：曾因只支持基础类型抛 unsupported type）")
    void pojoSerializesAsJson() {
        when(valueOps.get("k5")).thenReturn(null);
        Demo d = cm.get("k5", Demo.class, k -> new Demo(1L, "店"), 60);
        assertEquals(1L, d.id());
        assertEquals("店", d.name());
        verify(valueOps).set(eq("k5"), contains("\"name\":\"店\""), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("POJO 从 JSON 反序列化（L2 命中路径）")
    void pojoDeserializesFromJson() {
        when(valueOps.get("k6")).thenReturn("{\"id\":2,\"name\":\"b\"}");
        Demo d = cm.get("k6", Demo.class, k -> null, 60);
        assertEquals(2L, d.id());
        assertEquals("b", d.name());
    }

    @Test
    @DisplayName("空串缓存反序列化为 null（穿透空值语义）")
    void emptyStringYieldsNull() {
        when(valueOps.get("k7")).thenReturn("");
        assertNull(cm.get("k7", String.class, k -> "x", 60));
    }

    @Test
    @DisplayName("Long 类型直转")
    void longType() {
        when(valueOps.get("k8")).thenReturn("123");
        assertEquals(123L, cm.get("k8", Long.class, k -> null, 60));
    }

    @Test
    @DisplayName("非法 JSON -> 抛 IllegalStateException（不静默返回脏数据）")
    void badJsonThrows() {
        when(valueOps.get("k9")).thenReturn("not-a-json");
        assertThrows(IllegalStateException.class,
            () -> cm.get("k9", Demo.class, k -> null, 60));
    }

    @Test
    @DisplayName("invalidate 删除 L2 键")
    void invalidateRemoves() {
        cm.invalidate("k10");
        verify(redis).delete("k10");
    }
}
