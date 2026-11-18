package org.nearslot.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.nearslot.auth.dto.ChangePasswordRequest;
import org.nearslot.auth.dto.LoginRequest;
import org.nearslot.auth.dto.LoginResponse;
import org.nearslot.auth.service.LoginService;
import org.nearslot.auth.service.TokenService;
import org.nearslot.common.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;

/** 认证 API（FR-1）。 */
@Tag(name = "认证", description = "登录 / 验证码（FR-1）")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LoginService loginService;
    private final TokenService tokenService;
    /** 认证与风控参数（RT TTL 等）外置到 `nearslot.auth.*`。 */
    private final org.nearslot.auth.config.AuthProperties props;

    @Operation(summary = "登录", description = "支持 password / code 两种类型；BCrypt 校验 + IP/账号双维度防爆破")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        LoginResponse res = loginService.login(req, clientIp(request));
        // 评审 D-001：TokenService 生成了 RT，但此前**从未交付给客户端**，
        // 导致 /auth/refresh 永远拿不到 RT（前端 Cookie 与响应体都没有）。
        // 用 HttpOnly + SameSite=Lax Cookie 下发：JS 读不到（防 XSS 窃取），
        // 但浏览器会自动随同源请求携带；生产须加 Secure（由网关终止 TLS 时配置）。
        String rt = res.getRefreshToken();
        if (rt != null && !rt.isBlank()) {
            ResponseCookie cookie = ResponseCookie.from("Refresh-Token", rt)
                .httpOnly(true).secure(false).path("/api/v1/auth").sameSite("Lax")
                .maxAge(props.getRefreshTtlSeconds())
                .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        response.setHeader("X-Token-Refresh", "ok");
        return Result.ok(res);
    }

    /**
     * 验证码下发（FR-1.1）。
     * 注意：当前仅生成随机码写入 Redis 并返回（dev 便利），**未接短信网关**；
     * 生产必须改为下发短信且响应体中不得回显验证码。
     */
    @Operation(summary = "下发验证码", description = "生成 6 位验证码写入 Redis(60s)。生产需接短信网关，响应不得回显")
    @PostMapping("/code")
    public Result<String> sendCode(@RequestParam("phone") String phone) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        loginService.issueCode(phone, code);
        return Result.ok(code);
    }

    @Operation(summary = "刷新令牌", description = "旧 RT 换新 AT；并发刷新由 422 自愈返回最新 AT")
    @PostMapping("/refresh")
    public Result<RefreshResponse> refresh(@RequestHeader(value = "Refresh-Token", required = false) String rt,
                                  @CookieValue(value = "Refresh-Token", required = false) String cookieRt,
                                  HttpServletResponse response) {
        String refreshToken = (rt != null && !rt.isBlank()) ? rt : cookieRt;
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new org.nearslot.common.exception.BaseException(
                org.nearslot.common.enums.BaseCode.UNAUTHORIZED, "缺少 Refresh-Token");
        }
        Long userId = tokenService.parseUserIdFromRefresh(refreshToken);
        String newAt = tokenService.refresh(userId, refreshToken);
        response.setHeader("X-Token-Refresh", "ok");
        // 评审 F-007：前端 request.ts 按 body.data.accessToken 读取；
        // 此前返回裸字符串导致刷新后 token 仍是 undefined。
        return Result.ok(new RefreshResponse(newAt));
    }

    @Operation(summary = "修改密码", description = "校验原密码后改密，并 bump version 踢出所有旧会话")
    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                       HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            throw new org.nearslot.common.exception.BaseException(
                org.nearslot.common.enums.BaseCode.UNAUTHORIZED, "未登录");
        }
        loginService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return Result.ok(null);
    }

    @Operation(summary = "登出", description = "清理四键，旧 AT 立即失效")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId != null) tokenService.logout(userId);
        return Result.ok(null);
    }

    /** 刷新响应（与前端 RefreshResult 契约一致）。 */
    public record RefreshResponse(String accessToken) {}

    private Long currentUserId(HttpServletRequest request) {
        Object uid = request.getAttribute("userId");
        if (uid instanceof Long l) return l;
        String auth = request.getHeader("authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try { return tokenService.parseUserId(auth.substring(7).trim()); }
            catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    /** 取真实客户端 IP（Nginx 反代场景优先 X-Forwarded-For）。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
