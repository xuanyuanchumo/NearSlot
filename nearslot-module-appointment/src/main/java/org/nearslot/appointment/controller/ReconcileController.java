package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.ReconciliationTask;
import org.nearslot.common.Result;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 对账运维接口（内部使用）。
 * 正常由 @Scheduled 每 5 分钟自动执行；此接口用于故障排查时手动触发一次。
 */
@Tag(name = "对账运维", description = "手动触发对账（内部）")
@RestController
@RequestMapping("/api/v1/internal/reconcile")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconciliationTask reconciliationTask;

    /**
     * 管理密钥（.env 注入）。
     * 内部接口不走用户登录态，改用固定密钥头 X-Admin-Token 鉴权——
     * 避免「运维接口依赖某个用户会话」的耦合，也避免误暴露给普通用户。
     */
    @Value("${nearslot.admin.token:}")
    private String adminToken;

    @Operation(summary = "手动触发对账", description = "内部接口，需 X-Admin-Token 头")
    @PostMapping("/run")
    public Result<String> run(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (adminToken == null || adminToken.isBlank()) {
            throw new BaseException(BaseCode.FORBIDDEN, "未配置 nearslot.admin.token，内部接口已禁用");
        }
        if (!adminToken.equals(token)) {
            throw new BaseException(BaseCode.FORBIDDEN, "管理令牌无效");
        }
        reconciliationTask.reconcile();
        return Result.ok("对账已执行");
    }
}
