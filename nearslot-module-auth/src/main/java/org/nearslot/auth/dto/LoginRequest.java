package org.nearslot.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求（FR-1.1 手机验证码 / FR-1.2 密码）。 */
@Data
@Schema(description = "登录请求")
public class LoginRequest {

    @Schema(description = "手机号", example = "13800138000")
    @NotBlank(message = "手机号不能为空")
    private String phone;

    @Schema(description = "密码（密码登录时必填）")
    private String password;

    @Schema(description = "验证码（验证码登录时必填）")
    private String code;

    @Schema(description = "登录类型：password / code", example = "password")
    private String type = "password";
}
