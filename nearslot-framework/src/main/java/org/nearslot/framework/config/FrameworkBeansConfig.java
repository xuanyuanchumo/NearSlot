package org.nearslot.framework.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.nearslot.framework.bloom.BloomFilterHandler;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * framework 通用 Bean 装配（ADR-016）。
 * 这些类刻意不加 @Component，避免被业务模块扫描到而产生重复实例；
 * 统一在 app 侧通过本配置暴露。
 */
@Configuration
public class FrameworkBeansConfig {

    @Bean
    public CacheManager cacheManager(StringRedisTemplate redis,
                                     com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new CacheManager(redis, mapper);
    }

    @Bean
    public BloomFilterHandler bloomFilterHandler() {
        BloomFilterHandler handler = new BloomFilterHandler();
        // shop 过滤：5 万容量 / 1% 误判率（hmdp-plus 同款参数）
        handler.init("shop", 50_000, 0.01);
        return handler;
    }
}
