package org.nearslot.content.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.service.ReviewService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 评价控制器单测：参数解包与上下文透传（业务在 Service）。 */
class ReviewControllerTest {

    private ReviewService service;
    private ReviewController controller;

    @BeforeEach
    void setUp() {
        service = mock(ReviewService.class);
        controller = new ReviewController(service);
        UserHolder.set(1001L);
        UserHolder.setRole("MERCHANT");
        UserHolder.setShopId(1L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    @Test
    @DisplayName("create: 请求体解包为 (userId, orderId, rating, content, images, anonymous)")
    void createUnpacks() {
        ReviewController.CreateRequest req = new ReviewController.CreateRequest();
        req.setOrderId(9001L);
        req.setRating(5);
        req.setContent("很好");
        req.setImages("a.jpg");
        req.setAnonymous(true);
        when(service.create(1001L, 9001L, 5, "很好", "a.jpg", true)).thenReturn(1L);

        assertEquals(1L, controller.create(req).getData());
    }

    @Test
    @DisplayName("create: anonymous 缺省视为 false")
    void createDefaultsAnonymousFalse() {
        ReviewController.CreateRequest req = new ReviewController.CreateRequest();
        req.setOrderId(9001L);
        req.setRating(4);
        when(service.create(1001L, 9001L, 4, null, null, false)).thenReturn(2L);

        controller.create(req);

        verify(service).create(1001L, 9001L, 4, null, null, false);
    }

    @Test
    @DisplayName("reply: 透传角色与店铺上下文（鉴权在 Service）")
    void replyPassesContext() {
        ReviewController.ReplyRequest req = new ReviewController.ReplyRequest();
        req.setReply("感谢");

        controller.reply(1L, req);

        verify(service).reply(1001L, "MERCHANT", 1L, 1L, "感谢");
    }

    @Test
    @DisplayName("list: 分页参数缺省为 1/10")
    void listDefaultsPaging() {
        when(service.listByTechnician(eq(1001L), eq(2L), anyLong(), anyLong()))
            .thenReturn(PageResult.empty(1, 10));

        controller.byTechnician(2L, null, null);

        verify(service).listByTechnician(1001L, 2L, 1, 10);
    }

    @Test
    @DisplayName("stat: 透传技师 ID")
    void statForwards() {
        when(service.statOfTechnician(2L)).thenReturn(java.util.Map.of("count", 1));

        assertNotNull(controller.statOfTechnician(2L).getData());
        verify(service).statOfTechnician(2L);
    }

    @Test
    @DisplayName("delete: 透传当前用户")
    void deleteForwards() {
        controller.delete(5L);
        verify(service).delete(1001L, 5L);
    }
}
