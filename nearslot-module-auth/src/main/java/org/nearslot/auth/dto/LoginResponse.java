package org.nearslot.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 登录响应：AT 放 body，RT 走 HttpOnly Cookie（security/guidelines.md §1）。 */
@Data
@AllArgsConstructor
@Schema(description = "登录响应")
public class LoginResponse {

    @Schema(description = "访问令牌（30min）")
    private String accessToken;

    @Schema(description = "用户 ID（字符串，防精度丢失 ADR-012）")
    private String userId;

    @Schema(description = "会员等级，决定预约门槛")
    private Integer level;
}
