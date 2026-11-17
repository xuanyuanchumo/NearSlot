package org.nearslot.appointment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.TechRankService;
import org.nearslot.common.Result;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 热门技师榜（FR-4.3）。 */
@Tag(name = "技师榜", description = "每日热门技师 Top N（FR-4.3）")
@RestController
@RequestMapping("/api/v1/tech-rank")
@RequiredArgsConstructor
public class TechRankController {

    private final TechRankService techRankService;

    @Operation(summary = "每日热门技师榜", description = "按已支付成交单数倒序，TTL 90 天")
    @GetMapping("/{shopId}")
    public Result<List<Map<String, Object>>> top(
            @PathVariable("shopId") Long shopId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return Result.ok(techRankService.top(shopId,
            date == null ? LocalDate.now() : date, limit));
    }
}
