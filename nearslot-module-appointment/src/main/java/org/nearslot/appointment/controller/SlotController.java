package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.SlotPublishService;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 时段管理（FR-2.2）。
 *
 * **安全红线（2026-11-17 审计修复，Blocker）**：
 * 此前本控制器完全无鉴权，配合 WebMvcConfig 把 `/api/v1/slots` 整体排除，
 * 匿名请求即可 `POST /api/v1/slots` 发布时段（实测成功写库）。
 * 现在：GET 仍公开（LoginInterceptor 只读白名单），写方法要求登录 + 店铺归属校验。
 */
@Tag(name = "时段管理", description = "时段发布 / 关闭（FR-2.2）")
@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotPublishService slotPublishService;

    @Operation(summary = "发布时段", description = "落库 + 预热余量 + 广播缓存失效；需 MERCHANT/PLATFORM 且店铺归属匹配")
    @PostMapping
    public Result<Long> publish(@Valid @RequestBody PublishSlotRequest req) {
        requireShopOwner(req.getShopId());
        return Result.ok(slotPublishService.publish(req.getShopId(), req.getServiceStaffId(),
            req.getServiceItemId(), req.getStartsAt(), req.getEndsAt(),
            req.getCapacity(), req.getMinLevel()));
    }

    @Operation(summary = "服务商可约时段", description = "按服务商查询 available 时段及其余量（公开只读）")
    @GetMapping
    public Result<java.util.List<java.util.Map<String, Object>>> listByShop(
            @RequestParam("shopId") Long shopId,
            @RequestParam(value = "date", required = false) String date,
            @RequestParam(value = "serviceItemId", required = false) Long serviceItemId) {
        return Result.ok(slotPublishService.listAvailable(shopId, date, serviceItemId));
    }

    @Operation(summary = "关闭时段", description = "状态置关闭 + 删余量 + 广播；需店铺归属匹配")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable("id") Long id) {
        Long shopId = slotPublishService.shopIdOfSlot(id);
        requireShopOwner(shopId);
        slotPublishService.close(id);
        return Result.ok(null);
    }

    /**
     * 角色 + 归属闸（与 ShopAdminService#requireShopOwner 同语义）。
     * 跨模块不能复用 shop 模块的私有方法，故在控制器内做同构校验；
     * 角色/店铺来自 JWT claim（LoginInterceptor 已装配到 UserHolder）。
     */
    public static void requireShopOwner(Long targetShopId) {
        String role = UserHolder.role();
        if (!"MERCHANT".equals(role) && !"PLATFORM".equals(role)) {
            throw new BaseException(BaseCode.FORBIDDEN, "无管理权限 role=" + role);
        }
        if ("PLATFORM".equals(role)) return;
        Long own = UserHolder.shopId();
        if (own == null || !own.equals(targetShopId)) {
            throw new BaseException(BaseCode.FORBIDDEN, "越权操作其他店铺的时段");
        }
    }

    @Data
    public static class PublishSlotRequest {
        @NotNull private Long shopId;
        @NotNull private Long serviceStaffId;
        private Long serviceItemId;
        @NotNull private LocalDateTime startsAt;
        @NotNull private LocalDateTime endsAt;
        @NotNull private Integer capacity;
        private Integer minLevel;
    }
}
