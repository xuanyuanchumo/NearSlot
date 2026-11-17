package org.nearslot.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.nearslot.auth.dto.LoginRequest;
import org.nearslot.auth.dto.LoginResponse;
import org.nearslot.auth.service.LoginService;
import org.nearslot.common.Result;
import org.springframework.web.bind.annotation.*;

/** 认证 API（FR-1）。 */
@Tag(name = "认证", description = "登录 / 登出（FR-1）")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LoginService loginService;

    @Operation(summary = "登录", description = "手机号+密码登录，返回 AT；RT 由服务端写入 HttpOnly Cookie")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        LoginResponse res = loginService.login(req);
        response.setHeader("X-Token-Refresh", "ok");
        return Result.ok(res);
    }
}
