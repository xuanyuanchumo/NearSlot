package org.nearslot.framework.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 缓存三防之穿透：loader 返回 null 时空值缓存，写入空串防回源。 */
class CacheManagerTest {
    @SuppressWarnings("unchecked")
    @Test void nullValueIsCached() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String,String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        CacheManager cm = new CacheManager(redis, new com.fasterxml.jackson.databind.ObjectMapper());
        assertNull(cm.get("miss:1", String.class, k -> null, 60));
        verify(ops).set(eq("miss:1"), eq(""), anyLong(), any());
    }
}
