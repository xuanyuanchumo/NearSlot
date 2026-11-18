package org.nearslot.content.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.service.ReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 服务评价（FR-12）。
 *
 * 提交与撤回要求登录；技师/店铺评价列表公开只读（店铺详情页要能被未登录浏览）。
 */
@Tag(name = "评价", description = "评分与评价（FR-12，履约驱动）")
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "提交评价", description = "仅本人已完成订单可评，一单一评")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CreateRequest req) {
        return Result.ok(reviewService.create(UserHolder.get(), req.getOrderId(),
            req.getRating(), req.getContent(), req.getImages(),
            req.getAnonymous() != null && req.getAnonymous()));
    }

    @Operation(summary = "撤回评价", description = "仅本人；会回滚技师评分聚合")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        reviewService.delete(UserHolder.get(), id);
        return Result.ok(null);
    }

    @Operation(summary = "商家回复", description = "仅本店铺 MERCHANT 或 PLATFORM")
    @PostMapping("/{id}/reply")
    public Result<Void> reply(@PathVariable("id") Long id, @Valid @RequestBody ReplyRequest req) {
        reviewService.reply(UserHolder.get(), UserHolder.role(), UserHolder.shopId(),
            id, req.getReply());
        return Result.ok(null);
    }

    @Operation(summary = "技师评价列表", description = "公开只读")
    @GetMapping("/technicians/{technicianId}")
    public Result<PageResult<Map<String, Object>>> byTechnician(
            @PathVariable("technicianId") Long technicianId,
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size) {
        return Result.ok(reviewService.listByTechnician(UserHolder.get(), technicianId,
            page == null ? 1 : page, size == null ? 10 : size));
    }

    @Operation(summary = "技师评分概况", description = "均分与条数（公开）")
    @GetMapping("/technicians/{technicianId}/stat")
    public Result<Map<String, Object>> statOfTechnician(@PathVariable("technicianId") Long technicianId) {
        return Result.ok(reviewService.statOfTechnician(technicianId));
    }

    @Operation(summary = "店铺评价列表", description = "公开只读")
    @GetMapping("/shops/{shopId}")
    public Result<PageResult<Map<String, Object>>> byShop(
            @PathVariable("shopId") Long shopId,
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size) {
        return Result.ok(reviewService.listByShop(UserHolder.get(), shopId,
            page == null ? 1 : page, size == null ? 10 : size));
    }

    @Operation(summary = "我的评价")
    @GetMapping("/mine")
    public Result<PageResult<Map<String, Object>>> mine(
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "size", required = false) Long size) {
        return Result.ok(reviewService.mine(UserHolder.get(),
            page == null ? 1 : page, size == null ? 10 : size));
    }

    /** 提交评价请求体。 */
    @Data
    public static class CreateRequest {
        @NotNull(message = "orderId 不能为空")
        private Long orderId;
        /** 评分 1..5（上下限以 nearslot.review.rating-* 为准，此处只做粗校验）。 */
        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分最低 1")
        @Max(value = 5, message = "评分最高 5")
        private Integer rating;
        private String content;
        /** 逗号分隔图片 URL。 */
        private String images;
        private Boolean anonymous;
    }

    /** 商家回复请求体。 */
    @Data
    public static class ReplyRequest {
        @NotNull(message = "回复内容不能为空")
        private String reply;
    }
}
