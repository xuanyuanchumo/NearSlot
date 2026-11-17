package org.nearslot.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 改密请求（FR-1.4）。 */
@Data
@Schema(description = "修改密码请求")
public class ChangePasswordRequest {

    @Schema(description = "原密码")
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @Schema(description = "新密码，至少 8 位")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度需 8-64 位")
    private String newPassword;
}
