package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.api.CompletedOrderQuery;
import org.nearslot.common.api.TechnicianRatingUpdater;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.config.ReviewProperties;
import org.nearslot.content.entity.AppointmentReview;
import org.nearslot.content.mapper.AppointmentReviewMapper;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务评价（FR-12）。
 *
 * **闭环设计**：履约（订单 DONE）→ 评价（一单一评）→ 技师评分聚合 →
 * 影响"高分技师"检索与商家履约质量判断。
 *
 * 本项目化要点：
 * 1. **履约驱动**：经 {@link CompletedOrderQuery#reviewableOf} 校验订单已完成且属于本人，
 *    无履约不能打分（参考项目普遍允许任意用户评星，是刷分温床）；
 * 2. **一单一评由唯一键兜底**：应用层先查再插在并发下会双写，这里靠 uk_review_order；
 * 3. **评分聚合走契约**（{@link TechnicianRatingUpdater}）：content 不碰 technician 表；
 * 4. **时限**：超过 `nearslot.review.window-days` 天不允许再评（防"考古式"刷分）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final AppointmentReviewMapper reviewMapper;
    private final CompletedOrderQuery completedOrderQuery;
    private final TechnicianRatingUpdater ratingUpdater;
    private final ReviewProperties props;

    /** 提交评价。 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, Long orderId, int rating, String content,
                       String images, boolean anonymous) {
        validateRating(rating);
        String text = content == null ? "" : content.trim();
        if (text.length() > props.getMaxContentLength()) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "评价不能超过 " + props.getMaxContentLength() + " 字");
        }
        String img = normalizeImages(images);

        CompletedOrderQuery.ReviewableOrder order = completedOrderQuery.reviewableOf(userId, orderId);
        if (order == null) {
            throw new BaseException(BaseCode.FORBIDDEN,
                "仅本人已完成的预约可以评价（履约驱动，防刷分）");
        }
        assertWithinWindow(order.finishedAt());

        AppointmentReview r = new AppointmentReview();
        r.setId(IdGenerator.nextId());
        r.setOrderId(orderId);
        r.setUserId(userId);
        r.setShopId(order.shopId());
        r.setTechnicianId(order.technicianId());
        r.setServiceItemId(order.serviceItemId());
        r.setRating(rating);
        r.setContent(text);
        r.setImages(img);
        r.setAnonymous(anonymous ? 1 : 0);
        r.setStatus(1);
        try {
            reviewMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 一单一评：并发/重复提交由唯一键兜底，转成业务错误而非 500
            throw new BaseException(BaseCode.BAD_REQUEST, "该订单已评价过");
        }

        // 评分聚合：失败不应静默吞掉（会与订单评价数不一致），交给事务回滚
        ratingUpdater.applyRating(order.technicianId(), rating);
        log.info("评价已提交 reviewId={} orderId={} techId={} rating={}",
            r.getId(), orderId, order.technicianId(), rating);
        return r.getId();
    }

    /** 商家回复（仅该店铺 MERCHANT 或 PLATFORM）。 */
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long operatorId, String role, Long operatorShopId, Long reviewId, String reply) {
        AppointmentReview r = requireReview(reviewId);
        if (reply == null || reply.isBlank()) {
            throw new BaseException(BaseCode.BAD_REQUEST, "回复内容不能为空");
        }
        if (reply.length() > props.getMaxReplyLength()) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "回复不能超过 " + props.getMaxReplyLength() + " 字");
        }
        boolean platform = "PLATFORM".equals(role);
        boolean merchantOfShop = "MERCHANT".equals(role)
            && operatorShopId != null && operatorShopId.equals(r.getShopId());
        if (!platform && !merchantOfShop) {
            throw new BaseException(BaseCode.FORBIDDEN, "只能回复本店铺的评价");
        }
        // 用字符串列名而非 lambda：lambda 依赖 TableInfo 缓存，
        // 纯单测环境下未初始化会抛 "can not find lambda cache"（本项目已多次踩到）。
        reviewMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AppointmentReview>()
                .eq("id", reviewId)
                .set("merchant_reply", reply)
                .set("reply_at", LocalDateTime.now()));
    }

    /** 撤回自己的评价（同时回滚评分聚合）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long reviewId) {
        AppointmentReview r = requireReview(reviewId);
        if (!r.getUserId().equals(userId)) {
            throw new BaseException(BaseCode.FORBIDDEN, "只能删除自己的评价");
        }
        if (r.getStatus() != null && r.getStatus() == 2) return;  // 幂等
        reviewMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AppointmentReview>()
                .eq("id", reviewId)
                .set("status", 2));
        ratingUpdater.revertRating(r.getTechnicianId(), r.getRating());
        log.info("评价已撤回 reviewId={} techId={}", reviewId, r.getTechnicianId());
    }

    /** 技师评价列表（公开）。 */
    public PageResult<Map<String, Object>> listByTechnician(Long viewerId, Long technicianId,
                                                            long page, long size) {
        return pageQuery(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentReview>()
            .eq("technician_id", technicianId)
            .eq("status", 1)
            .orderByDesc("id"), viewerId, page, size);
    }

    /** 店铺评价列表（公开）。 */
    public PageResult<Map<String, Object>> listByShop(Long viewerId, Long shopId, long page, long size) {
        return pageQuery(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentReview>()
            .eq("shop_id", shopId)
            .eq("status", 1)
            .orderByDesc("id"), viewerId, page, size);
    }

    /** 我的评价。 */
    public PageResult<Map<String, Object>> mine(Long userId, long page, long size) {
        return pageQuery(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentReview>()
            .eq("user_id", userId)
            .orderByDesc("id"), userId, page, size);
    }

    /** 技师评分概况（均分与条数来自评价表，避免依赖 technician 冗余列做二次统计）。 */
    public Map<String, Object> statOfTechnician(Long technicianId) {
        List<AppointmentReview> all = reviewMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AppointmentReview>()
                .eq("technician_id", technicianId)
                .eq("status", 1));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("technicianId", String.valueOf(technicianId));
        m.put("count", all.size());
        m.put("avg", all.isEmpty() ? 0.0
            : Math.round(all.stream().mapToInt(AppointmentReview::getRating).average().orElse(0) * 100) / 100.0);
        return m;
    }

    // ---------- 内部 ----------

    private void validateRating(int rating) {
        if (rating < props.getRatingMin() || rating > props.getRatingMax()) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "评分必须在 " + props.getRatingMin() + "~" + props.getRatingMax() + " 之间");
        }
    }

    private void assertWithinWindow(LocalDateTime finishedAt) {
        if (finishedAt == null) return;   // 历史数据缺失时放行，避免误伤
        LocalDateTime deadline = finishedAt.plusDays(props.getWindowDays());
        if (LocalDateTime.now().isAfter(deadline)) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "已超过可评价期限（履约后 " + props.getWindowDays() + " 天内）");
        }
    }

    private String normalizeImages(String images) {
        if (images == null || images.isBlank()) return "";
        String[] parts = images.split(",");
        List<String> kept = java.util.Arrays.stream(parts)
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (kept.size() > props.getMaxImages()) {
            throw new BaseException(BaseCode.BAD_REQUEST,
                "最多上传 " + props.getMaxImages() + " 张图片");
        }
        String joined = String.join(",", kept);
        if (joined.length() > 2048) {
            throw new BaseException(BaseCode.BAD_REQUEST, "图片链接总长度超限");
        }
        return joined;
    }

    private AppointmentReview requireReview(Long reviewId) {
        AppointmentReview r = reviewMapper.selectById(reviewId);
        if (r == null || (r.getStatus() != null && r.getStatus() == 2)) {
            throw new BaseException(BaseCode.NOT_FOUND, "评价不存在");
        }
        return r;
    }

    private PageResult<Map<String, Object>> pageQuery(
            com.baomidou.mybatisplus.core.conditions.Wrapper<AppointmentReview> q,
            Long viewerId, long page, long size) {
        var p = reviewMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        List<Map<String, Object>> items = p.getRecords().stream()
            .map(r -> toView(r, viewerId)).toList();
        return PageResult.ofPage(items, p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 视图：匿名评价隐藏 userId（本人仍可见，便于"我的评价"回显）。 */
    private Map<String, Object> toView(AppointmentReview r, Long viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.getId()));
        boolean self = viewerId != null && viewerId.equals(r.getUserId());
        boolean anon = r.getAnonymous() != null && r.getAnonymous() == 1;
        m.put("userId", (anon && !self) ? null : String.valueOf(r.getUserId()));
        m.put("anonymous", anon);
        m.put("orderId", String.valueOf(r.getOrderId()));
        m.put("shopId", String.valueOf(r.getShopId()));
        m.put("technicianId", r.getTechnicianId() == null ? null : String.valueOf(r.getTechnicianId()));
        m.put("rating", r.getRating());
        m.put("content", r.getContent());
        m.put("images", r.getImages() == null || r.getImages().isBlank()
            ? List.of() : List.of(r.getImages().split(",")));
        m.put("merchantReply", r.getMerchantReply());
        m.put("replyAt", r.getReplyAt());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }
}
