package org.nearslot.shop.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.shop.config.ShopProperties;
import org.nearslot.shop.mapper.ShopMapper;
import org.nearslot.shop.mapper.TechnicianMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * GEO 命令的**运行时版本探测**（ADR-032 第 4 条 / FR-2.3）。
 *
 * 2026-11-18 在宿主机 Redis 3.0.5 上实测到 500：
 * `GEOSEARCH` 是 6.2 才引入的命令，在 Lua 里调用会得到
 * "Unknown Redis command called from Lua script"，而单测（mock）发现不了。
 *
 * 因此这里把"按版本选命令"钉成断言：
 * · >=6.2 -> GEOSEARCH
 * · <6.2  -> GEORADIUS（旧版兼容，7.x 仍可用）
 * · 探测失败 -> 回退 GEORADIUS（不让附近检索整体不可用）
 */
class ShopGeoCommandTest {

    private StringRedisTemplate redis;
    private ShopService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        service = new ShopService(
            mock(ShopMapper.class),
            mock(CacheManager.class),
            mock(BloomFilterHandler.class),
            redis,
            mock(TechnicianMapper.class),
            new ShopProperties());
    }

    /** 让 INFO server 返回指定版本。 */
    @SuppressWarnings("unchecked")
    private void stubVersion(String version) {
        when(redis.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            Properties p = new Properties();
            if (version != null) p.setProperty("redis_version", version);
            return p;
        });
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(List.of());
    }

    /** 捕获实际下发的 Lua 脚本正文，判断走的是哪条命令。 */
    @SuppressWarnings("unchecked")
    private String capturedScript() {
        var captor = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redis, atLeastOnce()).execute(captor.capture(), anyList(), any(Object[].class));
        return captor.getAllValues().stream()
            .map(s -> (String) s.getScriptAsString())
            .filter(s -> s.contains("GEO"))
            .findFirst().orElse("");
    }

    @Test
    @DisplayName("Redis >= 6.2 -> 使用 GEOSEARCH")
    void usesGeoSearchOnModernRedis() {
        stubVersion("7.2.4");

        service.searchNearby(120.15, 30.28, 5.0);

        String script = capturedScript();
        assertTrue(script.contains("GEOSEARCH"), "新版应走 GEOSEARCH，实际：" + script);
        assertFalse(script.contains("GEORADIUS"));
    }

    @Test
    @DisplayName("Redis 3.x -> 降级 GEORADIUS（否则 Lua 报 Unknown command）")
    void fallsBackToGeoRadiusOnLegacyRedis() {
        stubVersion("3.0.504");

        service.searchNearby(120.15, 30.28, 5.0);

        String script = capturedScript();
        assertTrue(script.contains("GEORADIUS"), "旧版必须降级 GEORADIUS，实际：" + script);
        assertTrue(script.contains("WITHDIST"), "GEORADIUS 需 WITHDIST 才能按距离排序");
    }

    @Test
    @DisplayName("探测失败（INFO 抛异常）-> 回退 GEORADIUS，不抛给调用方")
    void probeFailureFallsBackGracefully() {
        when(redis.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("INFO disabled"));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of());

        assertDoesNotThrow(() -> service.searchNearby(120.15, 30.28, 5.0));

        assertTrue(capturedScript().contains("GEORADIUS"), "探测失败应保守回退旧版命令");
    }

    @Test
    @DisplayName("版本探测只做一次（缓存标记，避免每次检索都 INFO）")
    void probeRunsOnce() {
        stubVersion("7.2.4");

        service.searchNearby(120.15, 30.28, 5.0);
        service.searchNearby(120.15, 30.28, 5.0);

        verify(redis, times(1)).execute(any(RedisCallback.class));
    }

    @Test
    @DisplayName("半径缺省取配置（不传 km 用 nearslot.shop.nearby-radius-km）")
    void usesConfiguredRadius() {
        stubVersion("7.2.4");
        var props = new ShopProperties();
        props.setNearbyRadiusKm(12.5);
        service = new ShopService(mock(ShopMapper.class), mock(CacheManager.class),
            mock(BloomFilterHandler.class), redis, mock(TechnicianMapper.class), props);

        service.searchNearby(120.15, 30.28, 0);

        var captor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(redis, atLeastOnce()).execute(any(RedisScript.class), anyList(), captor.capture());
        Object[] args = captor.getValue();
        assertEquals("12.5", args[2], "第三个 ARGV 应是半径 km");
    }
}
