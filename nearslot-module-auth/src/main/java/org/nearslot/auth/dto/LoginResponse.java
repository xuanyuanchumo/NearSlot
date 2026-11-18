package org.nearslot.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Setter;

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

    /**
     * 刷新令牌——**只在服务端流转**，用于 Controller 写 HttpOnly Cookie。
     * `@JsonIgnore` 保证它永不出现在响应 JSON 中（前端 JS 拿不到，防 XSS 窃取）。
     */
    @JsonIgnore
    @Setter
    private String refreshToken;
}
