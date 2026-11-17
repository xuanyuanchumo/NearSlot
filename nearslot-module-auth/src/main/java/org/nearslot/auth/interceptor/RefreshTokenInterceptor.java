package org.nearslot.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.auth.service.TokenService;
import org.nearslot.common.context.UserHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权 + 上下文装配（PickNear RefreshTokenInterceptor 语义）。
 * 响应头协议：X-Token-Refresh: ok|skipped|failed；401 带 X-Auth-Reason: superseded|no-session。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("authorization");
        if (auth == null || auth.isBlank()) {
            // 防御：不下发 Bearer null
            return true;
        }
        String token = auth.startsWith("Bearer ") ? auth.substring(7).trim() : auth.trim();
        if (token.isEmpty() || "null".equalsIgnoreCase(token)) {
            return true;
        }
        long start = System.currentTimeMillis();
        try {
            Long userId = tokenService.parseUserId(token);
            UserHolder.set(userId);
        } catch (RuntimeException e) {
            response.setHeader("X-Auth-Reason", "no-session");
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost > 100) log.warn("auth interceptor slow: {}ms", cost);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserHolder.clear();  // 防 Tomcat 线程复用污染
    }
}
