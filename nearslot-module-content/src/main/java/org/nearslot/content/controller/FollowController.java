package org.nearslot.content.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.content.service.FollowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 关注关系（FR-8 / ADR-028）。 */
@Tag(name = "社交关系", description = "关注 / 粉丝（FR-8）")
@RestController
@RequestMapping("/api/v1/follows")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @Operation(summary = "关注 / 取关", description = "幂等：重复操作返回 false，不重复计数")
    @PostMapping("/{targetUserId}")
    public Result<Boolean> follow(@PathVariable("targetUserId") Long targetUserId,
                                  @RequestParam(value = "follow", defaultValue = "true") boolean follow) {
        return Result.ok(followService.follow(UserHolder.get(), targetUserId, follow));
    }

    @Operation(summary = "是否已关注")
    @GetMapping("/{targetUserId}/status")
    public Result<Boolean> status(@PathVariable("targetUserId") Long targetUserId) {
        return Result.ok(followService.isFollowing(UserHolder.get(), targetUserId));
    }

    @Operation(summary = "我的关注列表")
    @GetMapping("/following")
    public Result<List<Long>> following() {
        return Result.ok(followService.following(UserHolder.get()));
    }

    @Operation(summary = "我的粉丝列表")
    @GetMapping("/followers")
    public Result<List<Long>> followers() {
        return Result.ok(followService.followers(UserHolder.get()));
    }

    @Operation(summary = "社交统计", description = "关注数/粉丝数/笔记数/获赞数（冗余计数，不 COUNT）")
    @GetMapping("/stat")
    public Result<Map<String, Object>> stat() {
        return Result.ok(followService.statOf(UserHolder.get()));
    }

    @Operation(summary = "他人社交统计")
    @GetMapping("/stat/{userId}")
    public Result<Map<String, Object>> statOf(@PathVariable("userId") Long userId) {
        return Result.ok(followService.statOf(userId));
    }
}
