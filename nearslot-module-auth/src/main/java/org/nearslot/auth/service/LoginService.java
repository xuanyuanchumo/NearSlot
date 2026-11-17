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
import org.nearslot.framework.ratelimit.RedissonRateLimiter;
import org.springframework.stereotype.Service;

/**
 * 登录编排（FR-1.1/1.2）。
 *
 * 本期范围（方案 A 垂直切片）：密码登录打通端到端链路。
 * 明确未实现：BCrypt 校验（当前为占位比对）、验证码下发与 ConsumeVerifyCode、
 * 防爆破计数、旧 MD5 哈希升级。这些列入 Phase 2 剩余任务，不在本轮伪装为已完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    private final UserPhoneMapper userPhoneMapper;
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final RedissonRateLimiter rateLimiter;

    /** 登录：手机号 + 密码 → 双 Token。 */
    public LoginResponse login(LoginRequest req) {
        if (!rateLimiter.tryAcquire("login")) {
            throw new BaseException(BaseCode.RATE_LIMITED);
        }
        UserPhone phone = userPhoneMapper.selectOne(
            Wrappers.<UserPhone>lambdaQuery().eq(UserPhone::getPhone, req.getPhone()));
        if (phone == null) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        User user = userMapper.selectById(phone.getUserId());
        if (user == null) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        // TODO(Phase 2): 改为 BCryptPasswordEncoder.matches，当前为占位等值比较
        if (req.getPassword() == null || !req.getPassword().equals(user.getPassword())) {
            throw new BaseException(BaseCode.UNAUTHORIZED, "账号或密码错误");
        }
        long version = tokenService.bumpVersion(user.getId());
        TokenService.TokenPair pair = tokenService.generateTokenPair(user.getId(), version);
        log.info("login ok userId={} level={}", user.getId(), user.getLevel());
        return new LoginResponse(pair.accessToken(), String.valueOf(user.getId()), user.getLevel());
    }
}
