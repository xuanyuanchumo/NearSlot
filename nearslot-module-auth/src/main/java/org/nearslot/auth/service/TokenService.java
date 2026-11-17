package org.nearslot.auth.service;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 双 Token + 版本号（ADR-002，PickNear 全套）。
 * 四键：login:token:access / login:token:refresh / token:version:valid / token:version:current。
 * 刷新不 bump version；改密/踢出才 bump。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {
    /** AT 有效期 30min；RT 7d。 */
    public static final long AT_TTL_SECONDS = 30 * 60L;
    public static final long RT_TTL_SECONDS = 7L * 24 * 3600;

    private final StringRedisTemplate redis;
    private final SecretKey jwtKey;

    private DefaultRedisScript<Long> script(String text) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(text);
        s.setResultType(Long.class);
        return s;
    }

    private static final String LOGIN_SET = "redis.call('SET',KEYS[1],ARGV[1],'EX',tonumber(ARGV[4])) "
        + "redis.call('SET',KEYS[2],ARGV[2],'EX',tonumber(ARGV[4])) "
        + "redis.call('SET',KEYS[3],ARGV[3],'EX',tonumber(ARGV[4])) "
        + "redis.call('SET',KEYS[4],ARGV[1],'EX',tonumber(ARGV[4])) return 200";

    /** 登录：签发 AT/RT 并四键原子写入。 */
    public TokenPair generateTokenPair(Long userId, Long version) {
        String at = buildAccessToken(userId, version);
        String rt = UUID.randomUUID().toString();
        String v = String.valueOf(version);
        redis.execute(script(LOGIN_SET),
            List.of(RedisConstants.TOKEN_ACCESS + userId,
                    RedisConstants.TOKEN_REFRESH + userId,
                    RedisConstants.TOKEN_VERSION_VALID + userId,
                    RedisConstants.TOKEN_VERSION_CURRENT + userId),
            at, rt, v, String.valueOf(RT_TTL_SECONDS));
        return new TokenPair(at, rt);
    }

    private String buildAccessToken(Long userId, Long version) {
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("version", version)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + AT_TTL_SECONDS * 1000))
            .signWith(jwtKey)
            .compact();
    }

    /** 校验 AT 的 JWT 合法性（版本校验由拦截器读 Redis 完成）。 */
    public Long parseUserId(String token) {
        try {
            String sub = Jwts.parser().verifyWith(jwtKey).build()
                .parseSignedClaims(token).getPayload().getSubject();
            return Long.valueOf(sub);
        } catch (RuntimeException e) {
            throw new BaseException(BaseCode.UNAUTHORIZED);
        }
    }

    /** 版本一致性（Caffeine 快拒由拦截器加，此处为 Redis 终校验）。 */
    public boolean versionMatches(Long userId, long version) {
        String v = redis.opsForValue().get(RedisConstants.TOKEN_VERSION_VALID + userId);
        return v != null && Long.parseLong(v) == version;
    }

    /** 改密/踢出：bump version，旧 AT 立即失效。 */
    public long bumpVersion(Long userId) {
        String key = RedisConstants.TOKEN_VERSION_VALID + userId;
        Long v = redis.opsForValue().increment(key);
        redis.expire(key, RedisConstants.TTL_8D, TimeUnit.SECONDS);
        return v == null ? 1L : v;
    }

    /** 登出：清理四键。 */
    public void logout(Long userId) {
        redis.delete(List.of(
            RedisConstants.TOKEN_ACCESS + userId,
            RedisConstants.TOKEN_REFRESH + userId,
            RedisConstants.TOKEN_VERSION_VALID + userId,
            RedisConstants.TOKEN_VERSION_CURRENT + userId));
    }

    public SecretKey jwtKeyFrom(String secret) {
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 双 Token 载体。 */
    public record TokenPair(String accessToken, String refreshToken) {}
}
