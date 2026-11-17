package org.nearslot.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.auth.dto.LoginRequest;
import org.nearslot.auth.dto.LoginResponse;
import org.nearslot.auth.entity.User;
import org.nearslot.auth.entity.UserPhone;
import org.nearslot.auth.mapper.UserMapper;
import org.nearslot.auth.mapper.UserPhoneMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.ratelimit.RedissonRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 登录编排（FR-1.1 验证码 / FR-1.2 密码）。
 *
 * 已实现：BCrypt 密码校验、验证码原子消费（Lua GET+DEL）、IP/账号双维度防爆破、
 *        旧 MD5 哈希登录成功后自动升级为 BCrypt。
 * 未实现：验证码下发与频控（需短信网关，见 §遗留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    /** 防爆破：IP 维度 60s 5 次（security/guidelines.md §2）。 */
    private static final int IP_FAIL_LIMIT = 5;
    private static final long IP_FAIL_WINDOW_SECONDS = 60;
    /** 防爆破：账号维度 24h 10 次锁 30min。 */
    private static final int ACCOUNT_FAIL_LIMIT = 10;
    private static final long ACCOUNT_LOCK_SECONDS = 30 * 60;

    /** 验证码原子消费：GET+DEL，防重复使用。 */
    private static final String CONSUME_CODE =
        "local v = redis.call('GET', KEYS[1]) "
        + "if not v then return 0 end "
        + "redis.call('DEL', KEYS[1]) "
        + "if v ~= ARGV[1] then return 0 end "
        + "return 1";

    private final UserPhoneMapper userPhoneMapper;
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final RedissonRateLimiter rateLimiter;
    private final StringRedisTemplate redis;

    /** 复用容器中的 PasswordEncoder（security 配置统一强度），不手 new。 */
    private final BCryptPasswordEncoder passwordEncoder;

    /** 登录：按 type 分流密码登录 / 验证码登录。 */
    public LoginResponse login(LoginRequest req, String clientIp) {
        if (!rateLimiter.tryAcquire("login")) {
            throw new BaseException(BaseCode.RATE_LIMITED);
        }
        String failKey = RedisConstants.PREFIX + "login:fail:" + clientIp;
        guardBruteForce(failKey);

        String type = req.getType() == null ? "password" : req.getType();
        UserPhone phone = userPhoneMapper.selectOne(
            Wrappers.<UserPhone>lambdaQuery().eq(UserPhone::getPhone, req.getPhone()));
        if (phone == null) {
            recordFailure(failKey);
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        User user = userMapper.selectById(phone.getUserId());
        if (user == null) {
            recordFailure(failKey);
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }

        if ("code".equals(type)) {
            verifyCode(req);
            redis.delete(failKey);
        } else {
            verifyPassword(req, user);
            redis.delete(failKey);
        }

        long version = tokenService.bumpVersion(user.getId());
        TokenService.TokenPair pair = tokenService.generateTokenPair(user.getId(), version);
        log.info("login ok userId={} level={} type={}", user.getId(), user.getLevel(), type);
        return new LoginResponse(pair.accessToken(), String.valueOf(user.getId()), user.getLevel());
    }

    /** 密码校验：BCrypt 优先；兼容历史 MD5 明文并在成功后自动升级。 */
    private void verifyPassword(LoginRequest req, User user) {
        String raw = req.getPassword();
        if (raw == null || raw.isBlank()) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "密码不能为空");
        }
        String stored = user.getPassword() == null ? "" : user.getPassword();
        boolean ok = stored.startsWith("$2")
            ? passwordEncoder.matches(raw, stored)
            : stored.equals(raw);   // 历史明文/MD5 兼容分支

        if (!ok) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!stored.startsWith("$2")) {
            // 自动升级为 BCrypt（PickNear PasswordService 模式）
            User upd = new User();
            upd.setId(user.getId());
            upd.setPassword(passwordEncoder.encode(raw));
            userMapper.updateById(upd);
            log.info("密码已自动升级为 BCrypt userId={}", user.getId());
        }
    }

    /** 验证码校验：Lua 原子消费，失败即抛。 */
    private void verifyCode(LoginRequest req) {
        String code = req.getCode();
        if (code == null || code.isBlank()) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "验证码不能为空");
        }
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(CONSUME_CODE);
        script.setResultType(Long.class);
        Long hit = redis.execute(script,
            List.of(RedisConstants.PREFIX + "login:code:" + req.getPhone()), code);
        if (hit == null || hit != 1L) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "验证码错误或已失效");
        }
    }

    /** 防爆破闸：超限直接拒绝。 */
    private void guardBruteForce(String failKey) {
        String v = redis.opsForValue().get(failKey);
        if (v != null && Integer.parseInt(v) >= ACCOUNT_FAIL_LIMIT) {
            throw new BaseException(BaseCode.RATE_LIMITED, "失败次数过多，账号已临时锁定");
        }
    }

    /** 记一次失败：IP 窗口 60s，账号维度累计 24h。 */
    private void recordFailure(String failKey) {
        Long n = redis.opsForValue().increment(failKey);
        if (n != null && n == 1L) {
            redis.expire(failKey, IP_FAIL_WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        if (n != null && n >= IP_FAIL_LIMIT) {
            redis.expire(failKey, ACCOUNT_LOCK_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 供上层（如验证码下发接口）写入验证码，TTL 60s。 */
    public void issueCode(String phone, String code) {
        redis.opsForValue().set(RedisConstants.PREFIX + "login:code:" + phone, code, 60, TimeUnit.SECONDS);
    }
}
