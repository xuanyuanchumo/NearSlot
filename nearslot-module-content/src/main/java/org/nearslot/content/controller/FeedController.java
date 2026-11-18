package org.nearslot.content.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.service.FeedService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 关注流（FR-8 / ADR-027）。 */
@Tag(name = "关注流", description = "关注的人的笔记（FR-8）")
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @Operation(summary = "关注流", description = "小V写扩散 + 大V读取时合并（ADR-027）")
    @GetMapping
    public Result<PageResult<Map<String, Object>>> timeline(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "10") long size) {
        return Result.ok(feedService.timeline(UserHolder.get(), page, size));
    }
}
