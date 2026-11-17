package org.nearslot.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.nearslot.auth.dto.LoginRequest;
import org.nearslot.auth.dto.LoginResponse;
import org.nearslot.auth.service.LoginService;
import org.nearslot.common.Result;
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

    @Operation(summary = "登录", description = "支持 password / code 两种类型；BCrypt 校验 + IP/账号双维度防爆破")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        LoginResponse res = loginService.login(req, clientIp(request));
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

    /** 取真实客户端 IP（Nginx 反代场景优先 X-Forwarded-For）。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
