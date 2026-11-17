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
 * 鉴权 + 上下文装配（ADR-002）。
 *
 * **两级版本校验**（security/guidelines.md §1）：
 *   1. JWT 签名/过期校验（parseUserId）；
 *   2. **Redis 版本一致性校验**（versionMatches）——这一步是"改密踢出"的核心。
 *
 * 实测踩坑（严重安全缺陷）：此前只做第 1 步，不校验 version，
 * 导致改密后 bumpVersion 完全无效，旧 AT 仍可访问所有受保护接口，
 * "改密踢出"形同虚设。
 *
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
            return true;   // 未带凭证：交给后续 LoginInterceptor 判定
        }
        String token = auth.startsWith("Bearer ") ? auth.substring(7).trim() : auth.trim();
        if (token.isEmpty() || "null".equalsIgnoreCase(token)) {
            return true;   // 防御：不下发 Bearer null
        }
        long start = System.currentTimeMillis();
        try {
            // 第 1 级：JWT 合法性
            Long userId = tokenService.parseUserId(token);
            // 第 2 级：版本一致性（改密/踢出后旧 AT 立即失效）
            long version = tokenService.versionOf(token);
            if (!tokenService.versionMatches(userId, version)) {
                // 标记原因供 LoginInterceptor 精确响应（api/design.md $4 要求区分）
                request.setAttribute("nearslot.auth.reason", "superseded");
                log.info("凭证已被顶替（版本不一致）userId={}", userId);
                return true;
            }
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
