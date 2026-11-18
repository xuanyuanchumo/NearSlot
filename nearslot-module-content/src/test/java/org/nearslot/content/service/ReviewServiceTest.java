package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.api.CompletedOrderQuery;
import org.nearslot.common.api.TechnicianRatingUpdater;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.config.ReviewProperties;
import org.nearslot.content.entity.AppointmentReview;
import org.nearslot.content.mapper.AppointmentReviewMapper;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 评价单测（FR-12）。
 *
 * 重点锁住四条本项目差异化规则：
 * 1. 无履约不能评（履约驱动，参考项目普遍允许任意评星）；
 * 2. 一单一评（唯一键兜底，应用层先查再插在并发下会双写）；
 * 3. 评分聚合必须调用契约（content 不得直接写 technician 表）；
 * 4. 超期不可评（window-days 配置）。
 */
class ReviewServiceTest {

    private AppointmentReviewMapper reviewMapper;
    private CompletedOrderQuery orderQuery;
    private TechnicianRatingUpdater ratingUpdater;
    private ReviewProperties props;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        reviewMapper = mock(AppointmentReviewMapper.class);
        orderQuery = mock(CompletedOrderQuery.class);
        ratingUpdater = mock(TechnicianRatingUpdater.class);
        props = new ReviewProperties();
        service = new ReviewService(reviewMapper, orderQuery, ratingUpdater, props);
    }

    private CompletedOrderQuery.ReviewableOrder order(LocalDateTime finishedAt) {
        return new CompletedOrderQuery.ReviewableOrder(9001L, 1L, 2L, 3L, finishedAt);
    }

    @Test
    @DisplayName("create: 未完成/非本人订单 -> 403（履约驱动，防刷分）")
    void rejectsWithoutCompletedOrder() {
        when(orderQuery.reviewableOf(1001L, 9001L)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 5, "很好", "", false));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(reviewMapper, never()).insert(any(AppointmentReview.class));
        verifyNoInteractions(ratingUpdater);
    }

    @Test
    @DisplayName("create: 正常评价 -> 落库 + 调用评分聚合契约")
    void createsAndAppliesRating() {
        when(orderQuery.reviewableOf(1001L, 9001L)).thenReturn(order(LocalDateTime.now().minusDays(1)));

        Long id = service.create(1001L, 9001L, 5, "手法专业", "a.jpg,b.jpg", false);

        assertNotNull(id);
        verify(reviewMapper).insert(argThat((AppointmentReview r) ->
            r.getOrderId().equals(9001L)
                && r.getShopId().equals(1L)
                && r.getTechnicianId().equals(2L)
                && r.getServiceItemId().equals(3L)
                && r.getRating() == 5
                && r.getStatus() == 1
                && "a.jpg,b.jpg".equals(r.getImages())));
        verify(ratingUpdater).applyRating(2L, 5);
    }

    @Test
    @DisplayName("create: 评分越界 -> 40001")
    void rejectsOutOfRangeRating() {
        BaseException low = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 0, "", "", false));
        assertEquals(BaseCode.BAD_REQUEST, low.getCode());

        BaseException high = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 6, "", "", false));
        assertEquals(BaseCode.BAD_REQUEST, high.getCode());
        verifyNoInteractions(ratingUpdater);
    }

    @Test
    @DisplayName("create: 重复评价（唯一键冲突）-> 40001，且不重复计分")
    void duplicateReviewDoesNotDoubleCount() {
        when(orderQuery.reviewableOf(1001L, 9001L)).thenReturn(order(LocalDateTime.now()));
        when(reviewMapper.insert(any(AppointmentReview.class)))
            .thenThrow(new DuplicateKeyException("uk_review_order"));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 5, "", "", false));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("已评价"));
        verifyNoInteractions(ratingUpdater);
    }

    @Test
    @DisplayName("create: 超过评价时限 -> 40001（window-days 配置生效）")
    void rejectsExpiredWindow() {
        props.setWindowDays(7);
        when(orderQuery.reviewableOf(1001L, 9001L))
            .thenReturn(order(LocalDateTime.now().minusDays(30)));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 5, "", "", false));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("期限"));
    }

    @Test
    @DisplayName("create: 正文超长 -> 40001")
    void rejectsTooLongContent() {
        props.setMaxContentLength(10);
        when(orderQuery.reviewableOf(1001L, 9001L)).thenReturn(order(LocalDateTime.now()));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 5, "x".repeat(11), "", false));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("create: 图片超过配置张数 -> 40001")
    void rejectsTooManyImages() {
        props.setMaxImages(2);
        when(orderQuery.reviewableOf(1001L, 9001L)).thenReturn(order(LocalDateTime.now()));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.create(1001L, 9001L, 5, "ok", "a,b,c", false));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("reply: 非本店铺 MERCHANT -> 403")
    void replyRejectsOtherShop() {
        AppointmentReview r = new AppointmentReview();
        r.setId(1L);
        r.setShopId(1L);
        r.setStatus(1);
        when(reviewMapper.selectById(1L)).thenReturn(r);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.reply(1002L, "MERCHANT", 2L, 1L, "感谢"));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(reviewMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("reply: 本店铺 MERCHANT 可回复")
    void replyByOwnMerchant() {
        AppointmentReview r = new AppointmentReview();
        r.setId(1L);
        r.setShopId(1L);
        r.setStatus(1);
        when(reviewMapper.selectById(1L)).thenReturn(r);

        assertDoesNotThrow(() -> service.reply(1002L, "MERCHANT", 1L, 1L, "感谢反馈"));
        verify(reviewMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("reply: PLATFORM 可回复任意店铺")
    void replyByPlatform() {
        AppointmentReview r = new AppointmentReview();
        r.setId(1L);
        r.setShopId(9L);
        r.setStatus(1);
        when(reviewMapper.selectById(1L)).thenReturn(r);

        assertDoesNotThrow(() -> service.reply(1003L, "PLATFORM", null, 1L, "已跟进"));
    }

    @Test
    @DisplayName("delete: 非本人 -> 403")
    void deleteRejectsOthers() {
        AppointmentReview r = new AppointmentReview();
        r.setId(1L);
        r.setUserId(2002L);
        r.setStatus(1);
        when(reviewMapper.selectById(1L)).thenReturn(r);

        BaseException ex = assertThrows(BaseException.class, () -> service.delete(1001L, 1L));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(ratingUpdater);
    }

    @Test
    @DisplayName("delete: 本人撤回 -> 逻辑删除 + 回滚评分聚合")
    void deleteRevertsRating() {
        AppointmentReview r = new AppointmentReview();
        r.setId(1L);
        r.setUserId(1001L);
        r.setTechnicianId(2L);
        r.setRating(4);
        r.setStatus(1);
        when(reviewMapper.selectById(1L)).thenReturn(r);
        // CAS 更新须返回影响行数 1；默认 0 会被判定为"已被并发撤回"（评审 BLOCKER-2 的正确行为）
        when(reviewMapper.update(isNull(), any())).thenReturn(1);

        service.delete(1001L, 1L);

        verify(reviewMapper).update(isNull(), any());
        verify(ratingUpdater).revertRating(2L, 4);
    }

    @Test
    @DisplayName("delete: 已删除 -> 404（requireReview 阶段拦截，不是 if 分支）")
    void deleteIsIdempotent() {
        AppointmentReview r = new AppointmentReview();
        r.setId(1L);
        r.setUserId(1001L);
        r.setStatus(2);
        when(reviewMapper.selectById(1L)).thenReturn(r);

        // 已隐藏的评价在 requireReview 阶段就会 404
        BaseException ex = assertThrows(BaseException.class, () -> service.delete(1001L, 1L));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(ratingUpdater);
    }

    @Test
    @DisplayName("statOfTechnician: 均分按有效评价计算")
    void statAggregates() {
        AppointmentReview a = new AppointmentReview();
        a.setRating(5);
        AppointmentReview b = new AppointmentReview();
        b.setRating(4);
        when(reviewMapper.selectList(any())).thenReturn(java.util.List.of(a, b));

        var stat = service.statOfTechnician(2L);

        assertEquals(2, stat.get("count"));
        assertEquals(4.5, stat.get("avg"));
    }
}
