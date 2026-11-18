package org.nearslot.content.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.entity.PointsLog;
import org.nearslot.content.service.PointService;
import org.nearslot.content.service.SignService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 签到与积分（FR-9 / ADR-029）。
 *
 * 只做"签到发积分/查余额/查流水"，**不实现券秒杀**（ADR-029 明确取舍）：
 * NearSlot 的稀缺资源是技师时段，再叠一套秒杀会造成两份并发内核。
 */
@Tag(name = "签到积分", description = "每日签到 / 积分账户（FR-9）")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class SignController {

    private final SignService signService;
    private final PointService pointService;

    @Operation(summary = "签到", description = "幂等：当天重复签到返回 fresh=false，不重复发积分")
    @PostMapping("/sign")
    public Result<Map<String, Object>> sign() {
        SignService.SignResult r = signService.sign(UserHolder.get());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fresh", r.fresh());
        m.put("continuousDays", r.continuousDays());
        m.put("points", r.points());
        m.put("balance", r.balance());
        return Result.ok(m);
    }

    @Operation(summary = "签到状态", description = "今日是否已签 + 当月签到日历 + 连续天数 + 余额")
    @GetMapping("/sign/status")
    public Result<Map<String, Object>> status() {
        Long uid = UserHolder.get();
        LocalDate now = LocalDate.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signedToday", signService.signedToday(uid));
        m.put("continuousDays", signService.continuousDays(uid, now));
        m.put("monthSignedDays", signService.monthSignedDays(uid, now));
        m.put("balance", pointService.balanceOf(uid));
        return Result.ok(m);
    }

    @Operation(summary = "积分余额")
    @GetMapping("/balance")
    public Result<Integer> balance() {
        return Result.ok(pointService.balanceOf(UserHolder.get()));
    }

    @Operation(summary = "积分流水", description = "可审计：每次变动都有记录与来源")
    @GetMapping("/logs")
    public Result<PageResult<PointsLog>> logs(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(pointService.logs(UserHolder.get(), page, size));
    }
}
