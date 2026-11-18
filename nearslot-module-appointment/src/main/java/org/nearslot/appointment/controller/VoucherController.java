package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.entity.AppointmentVoucher;
import org.nearslot.appointment.service.VoucherService;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.springframework.web.bind.annotation.*;

/**
 * 履约核销（FR-6.1）。
 *
 * 两类使用方：
 * · 用户侧 GET /orders/{orderId}/voucher —— 出示核销码；
 * · 商家侧 POST /vouchers/redeem 与 GET /vouchers —— 扫码核销、看待核销列表。
 * 商家侧按 UserHolder 的角色/归属店铺隔离（MERCHANT 只能看/核销自己店）。
 */
@Tag(name = "履约核销", description = "核销码签发 / 出示 / 核销（FR-6.1）")
@RestController
@RequestMapping("/api/v1/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    @Operation(summary = "按订单取核销码", description = "用户出示；归属校验在后端")
    @GetMapping("/orders/{orderId}")
    public Result<AppointmentVoucher> byOrder(@PathVariable("orderId") Long orderId) {
        return Result.ok(voucherService.findByOrder(UserHolder.get(), orderId));
    }

    @Operation(summary = "核销", description = "商家扫码；店铺隔离 + 幂等（重复核销返回业务错误）")
    @PostMapping("/redeem")
    public Result<Long> redeem(@RequestBody RedeemRequest req) {
        requireMerchant();
        // 平台运营 shopId 为 null，不限制店铺；商家只能用自己店的码
        Long shopId = "PLATFORM".equals(UserHolder.role()) ? null : UserHolder.shopId();
        return Result.ok(voucherService.redeem(req.getCode(), UserHolder.get(), shopId));
    }

    @Operation(summary = "待核销列表", description = "商家侧；按店铺隔离，MP 分页")
    @GetMapping
    public Result<PageResult<AppointmentVoucher>> page(
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        requireMerchant();
        Long shopId = UserHolder.shopId();
        if (shopId == null && !"PLATFORM".equals(UserHolder.role())) {
            throw new BaseException(BaseCode.FORBIDDEN, "当前账号未绑定店铺");
        }
        // 平台运营未指定店铺时给空页（避免越权看全平台，业务上也不需要）
        if (shopId == null) return Result.ok(PageResult.empty(page, size));
        return Result.ok(voucherService.pageByShop(shopId, status, page, size));
    }

    /** 角色闸：核销是商家侧操作。 */
    private void requireMerchant() {
        String role = UserHolder.role();
        if (!"MERCHANT".equals(role) && !"PLATFORM".equals(role)) {
            throw new BaseException(BaseCode.FORBIDDEN, "无核销权限 role=" + role);
        }
    }

    /** 核销请求体。 */
    @lombok.Data
    public static class RedeemRequest {
        @jakarta.validation.constraints.NotBlank(message = "核销码不能为空")
        private String code;
    }
}
