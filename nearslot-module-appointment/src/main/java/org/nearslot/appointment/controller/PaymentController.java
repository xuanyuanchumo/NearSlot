package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.PaymentService;
import org.nearslot.common.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 支付回调接口（FR-4.2）。
 *
 * 生产应由支付网关签名校验后调用；当前实现**未做网关签名验证**，
 * 上线前必须补（security/guidelines.md §5 密钥治理）。
 */
@Tag(name = "支付", description = "支付回调（FR-4.2）")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "支付成功回调", description = "幂等；订单已取消时转退款")
    @PostMapping("/callback")
    public Result<String> callback(@RequestBody PayCallbackRequest req) {
        PaymentService.PayResult r = paymentService.onPaid(req.getOrderId(), req.getPayNo());
        return Result.ok(r.name());
    }

    @Data
    public static class PayCallbackRequest {
        @NotNull(message = "orderId 不能为空")
        private Long orderId;
        @NotBlank(message = "payNo 不能为空")
        private String payNo;
    }
}
