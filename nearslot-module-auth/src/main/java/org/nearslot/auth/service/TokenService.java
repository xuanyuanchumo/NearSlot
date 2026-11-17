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
    private final org.nearslot.auth.mapper.UserRoleMapper userRoleMapper;

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
        // RT 编码 userId，便于刷新时反查（RT 是 UUID 无内嵌信息）。
        // 格式 "userId:uuid"，服务端可解析；泄露风险与纯 UUID 等同（仍是不可猜测随机值）。
        String rt = userId + ":" + UUID.randomUUID();
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
        // 角色与归属写入 JWT（多租户平台 P0 安全）：管理端据此鉴权与数据隔离
        String role = "CONSUMER";
        Long shopId = null;
        try {
            var roles = userRoleMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.nearslot.auth.entity.UserRole>lambdaQuery()
                    .eq(org.nearslot.auth.entity.UserRole::getUserId, userId));
            if (roles != null && !roles.isEmpty()) {
                // 签一个最高权限角色（演示用户同时有 CONSUMER/PLATFORM）
                var r = roles.stream()
                    .filter(x -> "PLATFORM".equals(x.getRole())).findFirst()
                    .orElse(roles.get(0));
                role = r.getRole();
                shopId = r.getShopId();
            }
        } catch (RuntimeException e) {
            // Token 签发不因角色查询失败而中断；默认 CONSUMER 最小权限
        }
        var b = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("version", version)
            .claim("role", role);
        if (shopId != null) b.claim("shopId", shopId);
        return b.issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + AT_TTL_SECONDS * 1000))
            .signWith(jwtKey)
            .compact();
    }

    /** 从 AT 取角色 claim。 */
    public String roleOf(String token) {
        try {
            Object v = Jwts.parser().verifyWith(jwtKey).build()
                .parseSignedClaims(token).getPayload().get("role");
            return v == null ? "CONSUMER" : String.valueOf(v);
        } catch (RuntimeException e) {
            return "CONSUMER";
        }
    }

    /** 从 AT 取归属店铺 claim；无则 null。 */
    public Long shopIdOf(String token) {
        try {
            Object v = Jwts.parser().verifyWith(jwtKey).build()
                .parseSignedClaims(token).getPayload().get("shopId");
            return v == null ? null : Long.valueOf(String.valueOf(v));
        } catch (RuntimeException e) {
            return null;
        }
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

    /** 从 AT 中提取 version claim（用于版本一致性校验）。 */
    public long versionOf(String token) {
        try {
            Object v = Jwts.parser().verifyWith(jwtKey).build()
                .parseSignedClaims(token).getPayload().get("version");
            return v == null ? -1L : Long.parseLong(String.valueOf(v));
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

    /**
     * 刷新：旧 RT 换新 AT（FR-1.3）。
     *
     * **422 并发自愈**（PickNear ReadCurrentToken.lua 语义）：
     * 两个并发请求带同一旧 RT 时，只有一个能成功替换；
     * 另一个发现 currentToken 已被更新，返回 422 + 当前最新 AT，
     * 而不是报错 401 —— 避免前端因并发刷新被踢下线。
     *
     * @return 新 AT；若并发被顶替则返回当前最新 AT
     */
    public String refresh(Long userId, String oldRefreshToken) {
        String rtKey = RedisConstants.TOKEN_REFRESH + userId;
        String currentRt = redis.opsForValue().get(rtKey);

        // RT 不存在：会话已失效，必须重新登录
        if (currentRt == null) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "会话已失效，请重新登录");
        }

        String atKey = RedisConstants.TOKEN_ACCESS + userId;
        String curKey = RedisConstants.TOKEN_VERSION_CURRENT + userId;

        // RT 不匹配：说明已被其他并发请求刷新过 → 422 自愈，返回最新 AT
        if (!currentRt.equals(oldRefreshToken)) {
            String latestAt = redis.opsForValue().get(curKey);
            if (latestAt != null) {
                // 自愈：把最新 AT 还给调用方，不打断用户体验
                return latestAt;
            }
            throw new BaseException(BaseCode.UNAUTHORIZED, "凭证已失效，请重新登录");
        }

        // 正常刷新：刷新**不 bump version**（ADR-002 取舍，减少写冲突）
        String version = redis.opsForValue().get(RedisConstants.TOKEN_VERSION_VALID + userId);
        long v = version == null ? 1L : Long.parseLong(version);
        String newAt = buildAccessToken(userId, v);
        redis.opsForValue().set(atKey, newAt, AT_TTL_SECONDS, TimeUnit.SECONDS);
        redis.opsForValue().set(curKey, newAt, RT_TTL_SECONDS, TimeUnit.SECONDS);
        return newAt;
    }

    /** 从 RT 解析 userId（格式 "userId:uuid"）。 */
    public Long parseUserIdFromRefresh(String refreshToken) {
        if (refreshToken == null || !refreshToken.contains(":")) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "Refresh-Token 格式非法");
        }
        try {
            return Long.valueOf(refreshToken.substring(0, refreshToken.indexOf(':')));
        } catch (NumberFormatException e) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "Refresh-Token 格式非法");
        }
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
