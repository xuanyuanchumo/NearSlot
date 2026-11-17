package org.nearslot.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.nearslot.common.context.UserHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 登录校验（order=1）：未登录返回 401 + X-Auth-Reason: no-session。 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserHolder.get() == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            // 精确原因：被顶替(superseded) vs 未登录(no-session)，前端据此决定静默刷新或跳登录
            Object reason = request.getAttribute("nearslot.auth.reason");
            response.setHeader("X-Auth-Reason",
                reason != null ? String.valueOf(reason) : "no-session");
            return false;
        }
        return true;
    }
}
