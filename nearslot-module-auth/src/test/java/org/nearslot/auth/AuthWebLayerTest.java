package org.nearslot.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.auth.controller.AuthController;
import org.nearslot.auth.interceptor.LoginInterceptor;
import org.nearslot.auth.service.LoginService;
import org.nearslot.auth.service.TokenService;
import org.nearslot.common.context.UserHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 认证 Web 层单测（BL-TEST-01）：拦截器放行/拦截 + 登录控制器契约。 */
class AuthWebLayerTest {

    @AfterEach
    void clean() { UserHolder.clear(); }

    private final LoginInterceptor loginInterceptor = new LoginInterceptor();

    @Test
    @DisplayName("LoginInterceptor: 公开 GET 路径匿名放行")
    void publicGetAllowed() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/slots");
        var resp = new MockHttpServletResponse();
        assertTrue(loginInterceptor.preHandle(req, resp, new Object()));
        assertEquals(200, resp.getStatus());
    }

    @Test
    @DisplayName("LoginInterceptor: 未登录访问非公开路径 -> 401 + X-Auth-Reason")
    void privatePathBlocked() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/appointments");
        var resp = new MockHttpServletResponse();
        assertFalse(loginInterceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
        assertEquals("no-session", resp.getHeader("X-Auth-Reason"));
    }

    @Test
    @DisplayName("LoginInterceptor: 已登录放行")
    void loggedInAllowed() throws Exception {
        UserHolder.set(1001L);
        var req = new MockHttpServletRequest("GET", "/api/v1/appointments");
        var resp = new MockHttpServletResponse();
        assertTrue(loginInterceptor.preHandle(req, resp, new Object()));
    }

    @Test
    @DisplayName("LoginInterceptor: 白名单按 GET 放行，POST /slots 仍需登录")
    void publicPostBlocked() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/slots");
        var resp = new MockHttpServletResponse();
        assertFalse(loginInterceptor.preHandle(req, resp, new Object()));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("AuthController: login 响应写 X-Token-Refresh 头")
    void loginSetsRefreshHeader() {
        var loginService = mock(LoginService.class);
        var tokenService = mock(TokenService.class);
        var controller = new AuthController(loginService, tokenService, new org.nearslot.auth.config.AuthProperties());
        var req = new org.nearslot.auth.dto.LoginRequest();
        req.setPhone("13800138000");
        req.setPassword("pass12345");
        var res = new org.nearslot.auth.dto.LoginResponse("at", "1001", 1, "1001:rt-uuid");
        when(loginService.login(any(), anyString())).thenReturn(res);

        var resp = new MockHttpServletResponse();
        var result = controller.login(req, new MockHttpServletRequest(), resp);

        assertEquals("ok", result.getCode() == 200 ? "ok" : "bad");
        assertEquals("ok", resp.getHeader("X-Token-Refresh"));
    }
}
