package org.nearslot.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.auth.config.AuthProperties;
import org.nearslot.framework.ratelimit.RedissonRateLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 评审 C-004：证明 auth 风控参数**改配置即改行为**，
 * 而不是"属性类能绑定 + 生产零消费"的假门禁。
 */
class LoginServiceConfigBehaviorTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AuthProperties props;
    private LoginService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        props = new AuthProperties();
        service = new LoginService(
            mock(org.nearslot.auth.mapper.UserPhoneMapper.class),
            mock(org.nearslot.auth.mapper.UserMapper.class),
            mock(TokenService.class),
            mock(RedissonRateLimiter.class),
            redis,
            new BCryptPasswordEncoder(),
            props);
    }

    @Test
    @DisplayName("max-fail-ip=2：第 1、2 次放行，第 3 次被拒（配置真正驱动行为）")
    void accountBudgetFollowsConfig() throws Exception {
        props.setMaxFailIp(2);
        when(valueOps.increment(anyString())).thenReturn(1L, 2L, 3L);
        var m = LoginService.class.getDeclaredMethod("acquireAccountBudget", String.class);
        m.setAccessible(true);

        assertTrue((Boolean) m.invoke(service, "13800138000"));
        assertTrue((Boolean) m.invoke(service, "13800138000"));
        assertFalse((Boolean) m.invoke(service, "13800138000"), "第 3 次应被拒");
    }

    @Test
    @DisplayName("login-code-ttl-seconds 配置被验证码下发真正使用")
    void codeTtlFollowsConfig() {
        props.setLoginCodeTtlSeconds(42);

        service.issueCode("13800138000", "123456");

        verify(valueOps).set(
            eq(org.nearslot.framework.cache.RedisConstants.LOGIN_CODE + "13800138000"),
            eq("123456"), eq(42L), eq(TimeUnit.SECONDS));
    }
}
