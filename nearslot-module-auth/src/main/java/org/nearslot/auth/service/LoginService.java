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
 * 防爆破严格对齐 PickNear `PasswordLoginStrategy` + `RedisConstants` 的精确语义：
 *   1. 账号锁 `login:lock:{phone}`（TTL 1800s）——锁定期内直接拒绝；
 *   2. 失败计数 `login:fail:count:{phone}`（TTL 86400s）——累计 ≥10 次即加锁并清零计数；
 *   3. IP 限频 `login:fail:ip:{ip}`（TTL 60s，上限 5）——常量已对齐，按 PickNear 现状
 *      未在主链路启用（其实现仅用账号维度），此处保留常量与能力，启用需另行 ADR。
 *   4. 登录成功即删除失败计数。
 *
 * 密码：BCrypt 校验；旧非 BCrypt 哈希登录成功后自动升级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

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
    private final BCryptPasswordEncoder passwordEncoder;

    /** 登录：按 type 分流密码 / 验证码。 */
    public LoginResponse login(LoginRequest req, String clientIp) {
        if (!rateLimiter.tryAcquire("login")) {
            throw new BaseException(BaseCode.RATE_LIMITED);
        }
        String phone = req.getPhone();
        String lockKey = RedisConstants.LOGIN_LOCK + phone;
        if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
            throw new BaseException(BaseCode.RATE_LIMITED, "账户已锁定，请 30 分钟后重试");
        }

        String type = req.getType() == null ? "password" : req.getType();
        UserPhone phoneRow = userPhoneMapper.selectOne(
            Wrappers.<UserPhone>lambdaQuery().eq(UserPhone::getPhone, phone));
        if (phoneRow == null) {
            recordFailure(phone);
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        User user = userMapper.selectById(phoneRow.getUserId());
        if (user == null) {
            recordFailure(phone);
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }

        if ("code".equals(type)) {
            verifyCode(phone, req.getCode());
        } else {
            verifyPassword(req, user);
        }
        // 登录成功清除失败计数（PickNear 同款）
        redis.delete(RedisConstants.LOGIN_FAIL_COUNT + phone);

        long version = tokenService.bumpVersion(user.getId());
        TokenService.TokenPair pair = tokenService.generateTokenPair(user.getId(), version);
        log.info("login ok userId={} level={} type={} ip={}", user.getId(), user.getLevel(), type, clientIp);
        return new LoginResponse(pair.accessToken(), String.valueOf(user.getId()), user.getLevel());
    }

    /** 密码校验：BCrypt 优先，旧哈希成功后自动升级。 */
    private void verifyPassword(LoginRequest req, User user) {
        String raw = req.getPassword();
        if (raw == null || raw.isBlank()) {
            recordFailure(req.getPhone());
            throw new BaseException(BaseCode.UNAUTHORIZED, "密码不能为空");
        }
        String stored = user.getPassword() == null ? "" : user.getPassword();
        boolean ok = stored.startsWith("$2")
            ? passwordEncoder.matches(raw, stored)
            : stored.equals(raw);

        if (!ok) {
            recordFailure(req.getPhone());
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!stored.startsWith("$2")) {
            User upd = new User();
            upd.setId(user.getId());
            upd.setPassword(passwordEncoder.encode(raw));
            userMapper.updateById(upd);
            log.info("密码已自动升级为 BCrypt userId={}", user.getId());
        }
    }

    /** 验证码校验：Lua 原子消费。 */
    private void verifyCode(String phone, String code) {
        if (code == null || code.isBlank()) {
            recordFailure(phone);
            throw new BaseException(BaseCode.UNAUTHORIZED, "验证码不能为空");
        }
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(CONSUME_CODE);
        script.setResultType(Long.class);
        Long hit = redis.execute(script, List.of(RedisConstants.LOGIN_CODE + phone), code);
        if (hit == null || hit != 1L) {
            recordFailure(phone);
            throw new BaseException(BaseCode.UNAUTHORIZED, "验证码错误或已失效");
        }
    }

    /**
     * 记一次失败（PickNear 精确语义）：
     * 计数 +1；首次设置 24h TTL；达 10 次则写锁(30min) 并清空计数。
     */
    private void recordFailure(String phone) {
        String failKey = RedisConstants.LOGIN_FAIL_COUNT + phone;
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redis.expire(failKey, RedisConstants.TTL_FAIL_COUNT, TimeUnit.SECONDS);
        }
        if (fails != null && fails >= RedisConstants.MAX_FAIL_COUNT) {
            redis.opsForValue().set(RedisConstants.LOGIN_LOCK + phone, "1",
                RedisConstants.TTL_LOGIN_LOCK, TimeUnit.SECONDS);
            redis.delete(failKey);
            log.warn("账户连续登录失败被锁定 phone={}", phone);
        }
    }

    /**
     * 修改密码（FR-1.4）。
     *
     * 安全要点（security/guidelines.md $1）：
     *   1. 必须先校验原密码，防止会话被劫持后直接改密；
     *   2. 新密码 BCrypt 哈希存储；
     *   3. **改密必须 bump version**，让所有旧 AT 立即失效（"改密踢出"）；
     *   4. 改密后清理 RT，强制重新登录。
     *
     * 若只改密码不 bump，被盗 Token 仍可继续使用，改密形同虚设。
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BaseException(BaseCode.NOT_FOUND, "用户不存在");

        String stored = user.getPassword() == null ? "" : user.getPassword();
        boolean ok = stored.startsWith("$2")
            ? passwordEncoder.matches(oldPassword, stored)
            : stored.equals(oldPassword);
        if (!ok) {
            recordFailure(String.valueOf(userId));
            throw new BaseException(BaseCode.UNAUTHORIZED, "原密码错误");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BaseException(BaseCode.INTERNAL_ERROR, "新密码不能与原密码相同");
        }

        User upd = new User();
        upd.setId(userId);
        upd.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(upd);

        // 关键：bump version -> 所有旧 AT 失效；同时清 RT 强制重登
        tokenService.bumpVersion(userId);
        tokenService.logout(userId);
        log.info("密码修改成功，已踢出所有会话 userId={}", userId);
    }

    /** 下发验证码（写入 Redis 60s）。 */
    public void issueCode(String phone, String code) {
        redis.opsForValue().set(RedisConstants.LOGIN_CODE + phone, code, 60, TimeUnit.SECONDS);
    }
}
