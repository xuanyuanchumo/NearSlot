package org.nearslot.auth.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.auth.service.TokenService;
import org.nearslot.common.context.UserHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** RefreshTokenInterceptor 单测（BL-TEST-01）：两级版本校验与上下文装配。 */
class RefreshTokenInterceptorTest {

    private TokenService tokenService;
    private RefreshTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        interceptor = new RefreshTokenInterceptor(tokenService);
    }

    @AfterEach
    void clean() { UserHolder.clear(); }

    @Test
    @DisplayName("无 authorization 头 -> 放行，交给 LoginInterceptor")
    void noHeader() throws Exception {
        var req = new MockHttpServletRequest();
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("Bearer null -> 防御性放行")
    void bearerNull() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("authorization", "Bearer null");
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("版本一致 -> 装配 userId/role/shopId 到上下文")
    void versionMatched() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("authorization", "Bearer valid-token");
        when(tokenService.parseUserId("valid-token")).thenReturn(1001L);
        when(tokenService.versionOf("valid-token")).thenReturn(3L);
        when(tokenService.versionMatches(1001L, 3L)).thenReturn(true);
        when(tokenService.roleOf("valid-token")).thenReturn("MERCHANT");
        when(tokenService.shopIdOf("valid-token")).thenReturn(1L);

        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));

        assertEquals(1001L, UserHolder.get());
        assertEquals("MERCHANT", UserHolder.role());
        assertEquals(1L, UserHolder.shopId());
    }

    @Test
    @DisplayName("版本不一致 -> 标记 superseded，不装配上下文（改密踢出核心）")
    void superseded() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("authorization", "Bearer old-token");
        when(tokenService.parseUserId("old-token")).thenReturn(1001L);
        when(tokenService.versionOf("old-token")).thenReturn(2L);
        when(tokenService.versionMatches(1001L, 2L)).thenReturn(false);

        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));

        assertEquals("superseded", req.getAttribute("nearslot.auth.reason"));
        assertNull(UserHolder.get(), "旧凭证不得装配上下文");
    }

    @Test
    @DisplayName("JWT 解析异常 -> 标记 no-session，不中断请求")
    void invalidToken() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("authorization", "Bearer bad-token");
        when(tokenService.parseUserId("bad-token"))
            .thenThrow(new org.nearslot.common.exception.BaseException(
                org.nearslot.common.enums.BaseCode.UNAUTHORIZED));
        var resp = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(req, resp, new Object()));
        assertEquals("no-session", resp.getHeader("X-Auth-Reason"));
        assertNull(UserHolder.get());
    }

    @Test
    @DisplayName("afterCompletion 清理上下文，防线程复用污染")
    void afterCompletionClearsContext() {
        UserHolder.set(1001L);
        interceptor.afterCompletion(new MockHttpServletRequest(),
            new MockHttpServletResponse(), new Object(), null);
        assertNull(UserHolder.get());
    }

    @Test
    @DisplayName("非 Bearer 前缀的原始 token 也接受")
    void rawTokenAccepted() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("authorization", "raw-token");
        when(tokenService.parseUserId("raw-token")).thenReturn(1001L);
        when(tokenService.versionOf("raw-token")).thenReturn(1L);
        when(tokenService.versionMatches(1001L, 1L)).thenReturn(true);

        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), new Object()));
        verify(tokenService).parseUserId("raw-token");
    }
}
