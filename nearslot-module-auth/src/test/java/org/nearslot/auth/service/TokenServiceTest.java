package org.nearslot.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** TokenService 单测：JWT 解析、刷新安全分支、生命周期（BL-TEST-01）。 */
class TokenServiceTest {

    private static final SecretKey KEY = Keys.hmacShaKeyFor(
        "test-jwt-secret-at-least-32-bytes-long-0123456789".getBytes(StandardCharsets.UTF_8));

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new TokenService(redis, KEY,
            mock(org.nearslot.auth.mapper.UserRoleMapper.class), new org.nearslot.auth.config.AuthProperties());
    }

    private String token(long userId, String role, long version) {
        return Jwts.builder().subject(String.valueOf(userId))
            .claim("userId", userId).claim("role", role).claim("version", version)
            .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(KEY).compact();
    }

    @Test
    @DisplayName("parseUserId: 合法 token -> userId")
    void parseUserId() {
        assertEquals(1001L, service.parseUserId(token(1001L, "CONSUMER", 1)));
    }

    @Test
    @DisplayName("roleOf: 从 JWT 取角色")
    void roleOf() {
        assertEquals("MERCHANT", service.roleOf(token(1001L, "MERCHANT", 1)));
    }

    @Test
    @DisplayName("versionOf: 从 JWT 取版本")
    void versionOf() {
        assertEquals(7L, service.versionOf(token(1001L, "CONSUMER", 7)));
    }

    @Test
    @DisplayName("shopIdOf: 从 JWT 取归属店铺；无 claim -> null")
    void shopIdOf() {
        String t = Jwts.builder().subject("1001").claim("shopId", 42L)
            .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(KEY).compact();
        assertEquals(42L, service.shopIdOf(t));
        assertNull(service.shopIdOf(token(1001L, "CONSUMER", 1)));
    }

    @Test
    @DisplayName("bumpVersion: Redis 自增并返回新版本号")
    void bumpVersion() {
        when(valueOps.increment(anyString())).thenReturn(5L);
        assertEquals(5L, service.bumpVersion(1001L));
        verify(redis).expire(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("versionMatches: Redis 版本一致 -> true")
    void versionMatches() {
        when(valueOps.get(anyString())).thenReturn("5");
        assertTrue(service.versionMatches(1001L, 5L));
        assertFalse(service.versionMatches(1001L, 4L));
    }

    @Test
    @DisplayName("refresh: RT 不存在 -> 401")
    void refreshNoRt() {
        when(valueOps.get(anyString())).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class,
            () -> service.refresh(1001L, "1001:abc"));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    @DisplayName("refresh: RT 不匹配 -> 401（防拼接伪造接管账号）")
    void refreshMismatchRejected() {
        when(valueOps.get(anyString())).thenReturn("1001:real-uuid");
        BaseException ex = assertThrows(BaseException.class,
            () -> service.refresh(1001L, "1001:attacker-fake"));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    @DisplayName("refresh: RT 匹配 -> 签发新 AT 并更新 Redis 双键")
    void refreshSuccess() {
        when(valueOps.get(anyString())).thenReturn("1001:rt-uuid", "5");
        String at = service.refresh(1001L, "1001:rt-uuid");
        assertNotNull(at);
        verify(valueOps, times(2)).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("parseUserIdFromRefresh: 非法格式 -> 401；合法 -> userId")
    void parseRefresh() {
        assertThrows(BaseException.class, () -> service.parseUserIdFromRefresh("no-colon"));
        assertThrows(BaseException.class, () -> service.parseUserIdFromRefresh("abc:uuid"));
        assertEquals(1001L, service.parseUserIdFromRefresh("1001:uuid"));
    }

    @Test
    @DisplayName("logout: 清理四键")
    void logoutDeletesFourKeys() {
        service.logout(1001L);
        verify(redis).delete(argThat((java.util.Collection<String> keys) -> keys.size() == 4));
    }

    @Test
    @DisplayName("jwtKeyFrom: 短密钥被拒绝（安全下限）")
    void jwtKeyFromRejectsShort() {
        assertThrows(RuntimeException.class, () -> service.jwtKeyFrom("short"));
    }

    @Test
    @DisplayName("generateTokenPair: 签发 AT + RT 并写 Redis")
    void generatePair() {
        var pair = service.generateTokenPair(1001L, 3L);
        assertNotNull(pair.accessToken());
        assertTrue(pair.refreshToken().startsWith("1001:"));
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
            anyList(), any(Object[].class));
    }
}
