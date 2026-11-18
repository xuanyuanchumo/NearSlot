package org.nearslot.content.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.nearslot.common.Result;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.entity.BlogComment;
import org.nearslot.content.service.BlogService;
import org.nearslot.content.service.CommentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 探店笔记（FR-7）。
 *
 * 发布必须带 orderId —— ADR-026 履约驱动：只有本人**已完成**的预约才能产出笔记。
 * 这是 NearSlot 相对两个参考项目的差异化能力（它们允许无订单发帖）。
 */
@Tag(name = "内容社区", description = "探店笔记 / 点赞 / 评论（FR-7）")
@RestController
@RequestMapping("/api/v1/blogs")
@RequiredArgsConstructor
public class BlogController {

    private final BlogService blogService;
    private final CommentService commentService;

    @Operation(summary = "发布笔记", description = "履约驱动：需本人已完成订单（orderId）")
    @PostMapping
    public Result<Long> publish(@Valid @RequestBody PublishRequest req) {
        return Result.ok(blogService.publish(UserHolder.get(), req.getOrderId(),
            req.getTitle(), req.getContent(), req.getImages()));
    }

    @Operation(summary = "热门笔记", description = "按点赞数倒序（冗余列，不聚合）")
    @GetMapping("/hot")
    public Result<PageResult<Map<String, Object>>> hot(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "10") long size) {
        return Result.ok(blogService.hot(UserHolder.get(), page, size));
    }

    @Operation(summary = "笔记详情")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return Result.ok(blogService.detail(UserHolder.get(), id));
    }

    @Operation(summary = "我的笔记")
    @GetMapping("/mine")
    public Result<PageResult<Map<String, Object>>> mine(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "10") long size) {
        return Result.ok(blogService.mine(UserHolder.get(), page, size));
    }

    @Operation(summary = "店铺相关笔记", description = "店铺详情页的用户评价入口")
    @GetMapping("/by-shop/{shopId}")
    public Result<PageResult<Map<String, Object>>> byShop(
            @PathVariable("shopId") Long shopId,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "10") long size) {
        return Result.ok(blogService.byShop(UserHolder.get(), shopId, page, size));
    }

    @Operation(summary = "点赞 / 取消点赞", description = "幂等：重复操作不重复计数")
    @PostMapping("/{id}/like")
    public Result<Boolean> like(@PathVariable("id") Long id,
                                @RequestParam(value = "like", defaultValue = "true") boolean like) {
        Long uid = UserHolder.get();
        return Result.ok(like ? blogService.like(uid, id) : blogService.unlike(uid, id));
    }

    @Operation(summary = "评论列表")
    @GetMapping("/{id}/comments")
    public Result<PageResult<BlogComment>> comments(
            @PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return Result.ok(commentService.list(id, page, size));
    }

    @Operation(summary = "发表评论", description = "parentId 非空即楼中楼回复")
    @PostMapping("/{id}/comments")
    public Result<Long> addComment(@PathVariable("id") Long id,
                                   @Valid @RequestBody CommentRequest req) {
        return Result.ok(commentService.add(UserHolder.get(), id, req.getParentId(), req.getContent()));
    }

    @Operation(summary = "删除评论")
    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable("commentId") Long commentId) {
        commentService.remove(UserHolder.get(), commentId);
        return Result.ok(null);
    }

    /** 发布笔记请求体。 */
    @Data
    public static class PublishRequest {
        /** ADR-026：必须有已完成的履约订单。 */
        @NotNull(message = "orderId 不能为空（仅已完成预约可发布笔记）")
        private Long orderId;
        private String title;
        @NotBlank(message = "笔记内容不能为空")
        private String content;
        /** 逗号分隔的图片 URL。 */
        private String images;
    }

    /** 评论请求体。 */
    @Data
    public static class CommentRequest {
        /** 回复的父评论（可空）。 */
        private Long parentId;
        @NotBlank(message = "评论内容不能为空")
        private String content;
    }
}
