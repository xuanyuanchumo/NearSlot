package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.dto.BookRequest;
import org.nearslot.appointment.service.AppointmentService;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.springframework.web.bind.annotation.*;

/** 预约 API（api/design.md §1：资源复数 + /api/v1 版本前缀）。 */
@Tag(name = "预约", description = "抢约 / Hold / 取消（FR-3）")
@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
public class AppointmentController {
    private final AppointmentService appointmentService;

    /** 抢约：POST /api/v1/appointments。 */
    @Operation(summary = "抢约", description = "Lua 三重检查（余量/一人一时段/技师时间轴）原子预扣，成功返回 orderId 并进入 Hold 待支付",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public Result<Long> book(@Valid @RequestBody BookRequest req) {
        Long orderId = appointmentService.book(UserHolder.get(), req.getSlotId());
        return Result.ok(orderId);
    }

    /** 取消预约：POST /api/v1/appointments/{id}/cancel。 */
    @Operation(summary = "取消预约", description = "状态机校验后回补 Redis（反转 beforeQty/afterQty，ADR-005）并异步触发候补提升",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable("id") Long id) {
        appointmentService.cancel(UserHolder.get(), id);
        return Result.ok(null);
    }
}
