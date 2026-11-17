package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.dto.BookRequest;
import org.nearslot.appointment.service.AppointmentService;
import org.nearslot.appointment.service.WaitlistService;
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
    private final WaitlistService waitlistService;

    /** 抢约：POST /api/v1/appointments。 */
    @Operation(summary = "抢约", description = "Lua 三重检查（余量/一人一时段/技师时间轴）原子预扣，成功返回 orderId 并进入 Hold 待支付",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping
    public Result<Long> book(@Valid @RequestBody BookRequest req) {
        Long orderId = appointmentService.book(UserHolder.get(), req.getSlotId());
        return Result.ok(orderId);
    }

    /** 订单详情：GET /api/v1/appointments/{id}（FR-4.1）。 */
    @Operation(summary = "订单详情", description = "按订单号查询；校验归属防止越权",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{id}")
    public Result<org.nearslot.appointment.entity.AppointmentOrder> detail(@PathVariable("id") Long id) {
        return Result.ok(appointmentService.queryOrder(UserHolder.get(), id));
    }

    /** 我的订单列表：GET /api/v1/appointments。 */
    @Operation(summary = "我的订单", description = "按状态过滤，游标分页",
        security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public Result<java.util.List<org.nearslot.appointment.entity.AppointmentOrder>> myOrders(
            @RequestParam(value = "status", required = false) Integer status) {
        return Result.ok(appointmentService.listMyOrders(UserHolder.get(), status));
    }

    /** 加入候补：POST /api/v1/appointments/{slotId}/waitlist。 */
    @Operation(summary = "加入候补", description = "ZSet 公平排序（时间戳+抖动），返回位次；已存在返回原序位",
        security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{slotId}/waitlist")
    public Result<Long> joinWaitlist(@PathVariable("slotId") Long slotId) {
        return Result.ok(waitlistService.join(slotId, UserHolder.get()));
    }

    /** 退出候补。 */
    @Operation(summary = "退出候补", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{slotId}/waitlist")
    public Result<Void> leaveWaitlist(@PathVariable("slotId") Long slotId) {
        waitlistService.leave(slotId, UserHolder.get());
        return Result.ok(null);
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
