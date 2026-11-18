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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** TokenService 单测：JWT 解析与刷新安全分支（BL-TEST-01 覆盖率补齐）。 */
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
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("userId", userId).claim("role", role).claim("version", version)
            .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(KEY).compact();
    }

    @Test
    @DisplayName("parseUserId: 自己签发的 token -> userId")
    void parseUserId() {
        // 用 buildAccessToken 同款算法（避免手工 JWT 与 verifyWith 的算法/键不一致）
        // 直接用同一 SecretKey 签一个带 subject 的 token，验证 parseUserId 的解析路径
        String at = token(1001L, "CONSUMER", 1);
        // 该 token 带 subject，parseUserId 应能读出 userId
        assertEquals(1001L, service.parseUserId(at));
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
    @DisplayName("parseUserIdFromRefresh: 格式非法 -> 401")
    void parseMalformedRt() {
        assertThrows(BaseException.class, () -> service.parseUserIdFromRefresh("no-colon"));
    }

    @Test
    @DisplayName("parseUserIdFromRefresh: 合法格式 -> userId")
    void parseValidRt() {
        assertEquals(1001L, service.parseUserIdFromRefresh("1001:uuid"));
    }
}
