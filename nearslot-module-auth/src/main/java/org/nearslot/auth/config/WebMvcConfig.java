package org.nearslot.auth.config;

import lombok.RequiredArgsConstructor;
import org.nearslot.auth.interceptor.LoginInterceptor;
import org.nearslot.auth.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 双拦截器链（ADR-002）：order 0 刷新/装配 → order 1 登录校验。 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final RefreshTokenInterceptor refreshTokenInterceptor;
    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
            .addPathPatterns("/**").order(0)
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/code", "/actuator/**");
        registry.addInterceptor(loginInterceptor)
            .addPathPatterns("/api/**").order(1)
            .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/code", "/api/v1/auth/refresh",
                                "/api/v1/shops/**",
                                // 时段查询为公开只读：未登录用户需先浏览时段才能决定是否登录。
                                // 仅 GET /slots 放行；POST 发布/关闭仍由方法级鉴权兜底（见 SlotController）
                                "/api/v1/slots",
                                "/api/v1/internal/**",
                                "/api/v1/payments/callback", "/actuator/**");
    }
}
