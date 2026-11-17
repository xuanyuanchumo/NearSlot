package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.SlotPublishService;
import org.nearslot.common.Result;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/** 时段管理（FR-2.2）。 */
@Tag(name = "时段管理", description = "时段发布 / 关闭（FR-2.2）")
@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotPublishService slotPublishService;

    @Operation(summary = "发布时段", description = "落库 + 预热余量 + 广播缓存失效")
    @PostMapping
    public Result<Long> publish(@Valid @RequestBody PublishSlotRequest req) {
        return Result.ok(slotPublishService.publish(req.getShopId(), req.getTechnicianId(),
            req.getServiceItemId(), req.getStartsAt(), req.getEndsAt(),
            req.getCapacity(), req.getMinLevel()));
    }

    @Operation(summary = "关闭时段", description = "状态置关闭 + 删余量 + 广播")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable("id") Long id) {
        slotPublishService.close(id);
        return Result.ok(null);
    }

    @Data
    public static class PublishSlotRequest {
        @NotNull private Long shopId;
        @NotNull private Long technicianId;
        private Long serviceItemId;
        @NotNull private LocalDateTime startsAt;
        @NotNull private LocalDateTime endsAt;
        @NotNull private Integer capacity;
        private Integer minLevel;
    }
}
