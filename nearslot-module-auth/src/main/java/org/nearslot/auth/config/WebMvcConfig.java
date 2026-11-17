package org.nearslot.auth.config;

import lombok.RequiredArgsConstructor;
import org.nearslot.auth.interceptor.LoginInterceptor;
import org.nearslot.auth.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 双拦截器链（ADR-002）：order 0 刷新/装配 → order 1 登录校验。
 *
 * **安全红线（2026-11-17 审计修复，Blocker）**：
 * 此前 `/api/v1/slots` 作为**精确路径整体**放行，而 excludePathPatterns 不区分 HTTP 方法，
 * 导致 `POST /api/v1/slots`（发布时段）完全免登录——实测未带 token 也能成功发布时段，
 * 任何人都能凭空造出可预约时段。注释里声称的「方法级鉴权兜底」实际不存在。
 *
 * 现改为：拦截器链**不再整体放行** /api/v1/slots，改由 {@link LoginInterceptor}
 * 在方法维度判断「公开只读」白名单（PUBLIC_GET_PATHS），写方法一律要求登录。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final RefreshTokenInterceptor refreshTokenInterceptor;
    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
            .addPathPatterns("/**").order(0)
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/code", "/api/v1/auth/refresh",
                                "/actuator/health", "/actuator/info");
        registry.addInterceptor(loginInterceptor)
            .addPathPatterns("/api/**").order(1)
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/code", "/api/v1/auth/refresh",
                                "/api/v1/shops/**",
                                // 探活基准端点免鉴权（压测需要测框架层吞吐，不被登录拦截干扰）
                                "/api/v1/system/**",
                                // 内部对账接口：走 X-Admin-Token 头鉴权（ReconcileController 自校验），
                                // 不依赖用户会话
                                "/api/v1/internal/**",
                                "/api/v1/payments/callback",
                                "/actuator/health", "/actuator/info");
        // 注意：/api/v1/slots 与 /api/v1/tech-rank 刻意**不在此放行**——
        // 它们的读接口由 LoginInterceptor 的只读白名单按 method 放行，写接口必须登录。
    }
}
