package org.nearslot.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.auth.config.AuthProperties;
import org.nearslot.auth.dto.ChangePasswordRequest;
import org.nearslot.auth.service.LoginService;
import org.nearslot.auth.service.TokenService;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** AuthController 端点单测（BL-TEST-01）：refresh/code/password/logout。 */
class AuthControllerEndpointTest {

    private LoginService loginService;
    private TokenService tokenService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        loginService = mock(LoginService.class);
        tokenService = mock(TokenService.class);
        controller = new AuthController(loginService, tokenService, new AuthProperties());
    }

    @AfterEach
    void clean() { UserHolder.clear(); }

    @Test
    @DisplayName("refresh: 缺 RT -> 401")
    void refreshNoToken() {
        BaseException ex = assertThrows(BaseException.class,
            () -> controller.refresh(null, null, new MockHttpServletResponse()));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    @DisplayName("refresh: 从 header 取 RT，返回 RefreshResponse 对象（F-007 契约）")
    void refreshFromHeader() {
        when(tokenService.parseUserIdFromRefresh("1001:rt")).thenReturn(1001L);
        when(tokenService.refresh(1001L, "1001:rt")).thenReturn("new-at");
        var resp = new MockHttpServletResponse();

        var result = controller.refresh("1001:rt", null, resp);

        assertEquals("new-at", ((AuthController.RefreshResponse) result.getData()).accessToken());
        assertEquals("ok", resp.getHeader("X-Token-Refresh"));
    }

    @Test
    @DisplayName("refresh: header 为空时从 Cookie 取 RT")
    void refreshFromCookie() {
        when(tokenService.parseUserIdFromRefresh("1001:rt")).thenReturn(1001L);
        when(tokenService.refresh(1001L, "1001:rt")).thenReturn("new-at");

        var result = controller.refresh(null, "1001:rt", new MockHttpServletResponse());

        assertEquals("new-at", ((AuthController.RefreshResponse) result.getData()).accessToken());
    }

    @Test
    @DisplayName("sendCode: 6 位验证码写入 Redis 并回显（dev 便利）")
    void sendCode() {
        var res = controller.sendCode("13800138000");
        assertTrue(res.getData().matches("\\d{6}"));
        verify(loginService).issueCode(eq("13800138000"), anyString());
    }

    @Test
    @DisplayName("changePassword: 未登录 -> 401")
    void changePasswordNotLoggedIn() {
        var req = new ChangePasswordRequest();
        req.setOldPassword("old");
        req.setNewPassword("new");
        BaseException ex = assertThrows(BaseException.class,
            () -> controller.changePassword(req, new MockHttpServletRequest()));
        assertEquals(BaseCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    @DisplayName("changePassword: 已登录 -> 调服务改密")
    void changePasswordLoggedIn() {
        UserHolder.set(1001L);
        var req = new ChangePasswordRequest();
        req.setOldPassword("old");
        req.setNewPassword("new");
        var httpReq = new MockHttpServletRequest();
        httpReq.setAttribute("userId", 1001L);

        controller.changePassword(req, httpReq);

        verify(loginService).changePassword(1001L, "old", "new");
    }

    @Test
    @DisplayName("logout: 已登录 -> 清理会话")
    void logoutLoggedIn() {
        UserHolder.set(1001L);
        var req = new MockHttpServletRequest();
        req.setAttribute("userId", 1001L);

        controller.logout(req);

        verify(tokenService).logout(1001L);
    }

    @Test
    @DisplayName("logout: 未登录 -> 幂等不抛")
    void logoutNotLoggedIn() {
        assertDoesNotThrow(() -> controller.logout(new MockHttpServletRequest()));
        verifyNoInteractions(tokenService);
    }
}
