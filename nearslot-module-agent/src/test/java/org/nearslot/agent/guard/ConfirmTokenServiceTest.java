package org.nearslot.agent.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 确认令牌单测（FR-5.2：TTL 300s + 一次性 + 归属校验）。 */
class ConfirmTokenServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ConfirmTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new ConfirmTokenService(redis);
    }

    @Test
    @DisplayName("签发令牌写入 Redis 且 TTL=300s")
    void issueStoresWithTtl() {
        String token = service.issue(1001L, "取消预约 3001");
        assertNotNull(token);
        verify(valueOps).set(contains(token), eq("1001:取消预约 3001"), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("消费成功 -> 删除令牌（一次性，防重放）")
    void consumeDeletesToken() {
        when(valueOps.get(anyString())).thenReturn("1001:取消预约");
        assertTrue(service.consume(1001L, "tok123"));
        verify(redis).delete(anyString());
    }

    @Test
    @DisplayName("令牌不存在/已过期 -> 拒绝")
    void consumeRejectsMissing() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertFalse(service.consume(1001L, "tok123"));
    }

    @Test
    @DisplayName("归属不符 -> 拒绝且不删除")
    void consumeRejectsWrongOwner() {
        when(valueOps.get(anyString())).thenReturn("2002:别人的操作");
        assertFalse(service.consume(1001L, "tok123"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("空令牌 -> 直接拒绝")
    void consumeRejectsBlank() {
        assertFalse(service.consume(1001L, null));
        assertFalse(service.consume(1001L, "  "));
    }
}
