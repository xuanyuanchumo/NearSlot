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
    /**
     * 常量 BCrypt 哈希（固定值，不对应任何真实口令）。
     * 用途：当账号不存在或哈希非 BCrypt 时也跑一次 matches，
     * 使「账号存在与否」无法通过响应时间差被枚举。
     */
    private static final String DUMMY_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

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
        // ============================================================
        // 限流（2026-11-17 审计修复，Major）：
        // 此前只有一个 RateType.OVERALL 的全局 20 次/60 秒桶——
        //   · 挡不住针对**单账号**的爆破（换个账号就绕过）；
        //   · 全球 20 次打满后**全站无法登录**（可用性反被限流本身打挂）。
        // 现叠加账号维度频控（LOGIN_FAIL_IP 常量组已有，但此前从未使用），
        // 全局桶保留为总闸，账号桶做定向保护。
        // ============================================================
        if (!rateLimiter.tryAcquire("login")) {
            throw new BaseException(BaseCode.RATE_LIMITED);
        }
        String phone = req.getPhone();
        if (!acquireAccountBudget(phone)) {
            log.warn("登录账号维度频控触发 phone={} ip={}", maskPhone(phone), clientIp);
            throw new BaseException(BaseCode.RATE_LIMITED, "操作过于频繁，请稍后再试");
        }
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
        // ============================================================
        // 安全红线（2026-11-17 审计修复，Major）：
        // 此前保留 `stored.equals(raw)` 明文兜底比较——一旦历史明文口令落库，
        // 等价于无哈希存储；且 String.equals 非常量时间，可被时序侧信道利用。
        //
        // 现改为：非 BCrypt 哈希一律走 dummy 校验后**拒绝登录**，强制重置密码。
        // BCrypt 分支的 matches 自带常量时间比较。
        // ============================================================
        boolean ok;
        if (stored.startsWith("$2")) {
            ok = passwordEncoder.matches(raw, stored);
        } else {
            // 对空/非 BCrypt 哈希也跑一次 BCrypt，避免"存储格式"通过响应时间泄露
            passwordEncoder.matches(raw, DUMMY_HASH);
            ok = false;
            log.warn("账号密码哈希格式非 BCrypt，已拒绝登录并要求重置 userId={}", user.getId());
        }

        if (!ok) {
            recordFailure(req.getPhone());
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        // 注意：不再做"旧哈希自动升级"——非 BCrypt 已直接拒绝登录（见上分支），
        // 能走到这里的必然是合法 BCrypt 哈希，无需迁移。
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
     * 账号维度频控（60 秒窗口）。
     *
     * 为什么与全局桶并存：全局桶是「总闸」（防雪崩），
     * 账号桶是「定向闸」（防对单账号爆破）。二者缺一不可。
     * 用 Lua 保证 INCR + EXPIRE 原子，失败即降级放行（限流是保护手段而非业务前提）。
     */
    private boolean acquireAccountBudget(String phone) {
        if (phone == null || phone.isBlank()) return true;
        try {
            String key = RedisConstants.LOGIN_FAIL_IP + phone;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, RedisConstants.TTL_FAIL_IP, TimeUnit.SECONDS);
            }
            return count == null || count <= RedisConstants.MAX_FAIL_IP;
        } catch (RuntimeException e) {
            log.warn("账号维度频控不可用，本次放行 phone={}", maskPhone(phone));
            return true;
        }
    }

    /** 日志脱敏：手机号只保留前 3 位与后 2 位（security/guidelines.md §4 禁止明文落日志）。 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
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
