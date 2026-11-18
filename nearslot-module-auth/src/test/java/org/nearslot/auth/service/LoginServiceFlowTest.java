package org.nearslot.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.auth.config.AuthProperties;
import org.nearslot.auth.dto.LoginRequest;
import org.nearslot.auth.entity.User;
import org.nearslot.auth.entity.UserPhone;
import org.nearslot.auth.mapper.UserMapper;
import org.nearslot.auth.mapper.UserPhoneMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.ratelimit.RedissonRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** LoginService 主流程单测（BL-TEST-01）：登录/限流/锁定/账号不存在分支。 */
class LoginServiceFlowTest {

    private UserPhoneMapper phoneMapper;
    private UserMapper userMapper;
    private TokenService tokenService;
    private RedissonRateLimiter rateLimiter;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private BCryptPasswordEncoder encoder;
    private AuthProperties props;
    private LoginService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        phoneMapper = mock(UserPhoneMapper.class);
        userMapper = mock(UserMapper.class);
        tokenService = mock(TokenService.class);
        rateLimiter = mock(RedissonRateLimiter.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        encoder = new BCryptPasswordEncoder();
        props = new AuthProperties();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(tokenService.bumpVersion(anyLong())).thenReturn(1L);
        when(tokenService.generateTokenPair(anyLong(), anyLong()))
            .thenReturn(new TokenService.TokenPair("at", "rt"));
        service = new LoginService(phoneMapper, userMapper, tokenService, rateLimiter,
            redis, encoder, props);
    }

    private LoginRequest req(String type) {
        LoginRequest r = new LoginRequest();
        r.setPhone("13800138000");
        r.setPassword("pass12345");
        r.setType(type);
        return r;
    }

    private User user() {
        User u = new User();
        u.setId(1001L);
        u.setPassword(encoder.encode("pass12345"));
        u.setLevel(5);
        return u;
    }

    @Test
    @DisplayName("全局限流触发 -> RATE_LIMITED")
    void globalRateLimit() {
        when(rateLimiter.tryAcquire("login")).thenReturn(false);
        BaseException ex = assertThrows(BaseException.class,
            () -> service.login(req("password"), "127.0.0.1"));
        assertEquals(BaseCode.RATE_LIMITED, ex.getCode());
    }

    @Test
    @DisplayName("账号锁定 -> RATE_LIMITED，不查库")
    void accountLocked() {
        when(redis.hasKey(anyString())).thenReturn(true);
        BaseException ex = assertThrows(BaseException.class,
            () -> service.login(req("password"), "127.0.0.1"));
        assertEquals(BaseCode.RATE_LIMITED, ex.getCode());
        verifyNoInteractions(phoneMapper);
    }

    @Test
    @DisplayName("手机号不存在 -> UNAUTHORIZED + 记失败")
    void phoneNotFound() {
        when(phoneMapper.selectOne(any())).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class,
            () -> service.login(req("password"), "127.0.0.1"));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
        verify(valueOps, atLeastOnce()).increment(contains("login:fail:count"));
    }

    @Test
    @DisplayName("密码正确 -> 签发 TokenPair 返回 LoginResponse（RT 供 Controller 写 Cookie）")
    void passwordLoginSuccess() {
        UserPhone up = new UserPhone();
        up.setUserId(1001L);
        up.setPhone("13800138000");
        when(phoneMapper.selectOne(any())).thenReturn(up);
        when(userMapper.selectById(1001L)).thenReturn(user());

        var res = service.login(req("password"), "127.0.0.1");

        assertEquals("at", res.getAccessToken());
        assertEquals("1001", res.getUserId());
        assertEquals("rt", res.getRefreshToken());
    }

    @Test
    @DisplayName("密码错误 -> UNAUTHORIZED + 记失败")
    void wrongPassword() {
        UserPhone up = new UserPhone();
        up.setUserId(1001L);
        when(phoneMapper.selectOne(any())).thenReturn(up);
        User u = user();
        u.setPassword(encoder.encode("different-password"));
        when(userMapper.selectById(1001L)).thenReturn(u);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.login(req("password"), "127.0.0.1"));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    @DisplayName("非 BCrypt 哈希 -> 拒绝登录（防历史明文口令兜底）")
    void nonBcryptHashRejected() {
        UserPhone up = new UserPhone();
        up.setUserId(1001L);
        when(phoneMapper.selectOne(any())).thenReturn(up);
        User u = user();
        u.setPassword("plain-text-password");
        when(userMapper.selectById(1001L)).thenReturn(u);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.login(req("password"), "127.0.0.1"));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    @DisplayName("验证码为空 -> UNAUTHORIZED")
    void emptyCode() {
        UserPhone up = new UserPhone();
        up.setUserId(1001L);
        when(phoneMapper.selectOne(any())).thenReturn(up);
        when(userMapper.selectById(1001L)).thenReturn(user());

        BaseException ex = assertThrows(BaseException.class,
            () -> service.login(req("code"), "127.0.0.1"));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }
}
