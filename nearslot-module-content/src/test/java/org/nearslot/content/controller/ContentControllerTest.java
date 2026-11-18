package org.nearslot.content.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.context.UserHolder;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.service.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 内容模块控制器单测：验证参数编排与角色闸（业务在 Service）。 */
class ContentControllerTest {

    private BlogService blogService;
    private CommentService commentService;
    private FollowService followService;
    private FeedService feedService;
    private SignService signService;
    private PointService pointService;
    private ShopTypeService typeService;

    @BeforeEach
    void setUp() {
        blogService = mock(BlogService.class);
        commentService = mock(CommentService.class);
        followService = mock(FollowService.class);
        feedService = mock(FeedService.class);
        signService = mock(SignService.class);
        pointService = mock(PointService.class);
        typeService = mock(ShopTypeService.class);
        UserHolder.set(1001L);
    }

    @AfterEach
    void tearDown() {
        UserHolder.clear();
    }

    @Test
    @DisplayName("BlogController: 发布请求体解包为 (userId, orderId, title, content, images)")
    void blogPublishUnpacks() {
        BlogController c = new BlogController(blogService, commentService);
        BlogController.PublishRequest req = new BlogController.PublishRequest();
        req.setOrderId(9001L);
        req.setTitle("t");
        req.setContent("c");
        req.setImages("a.jpg");
        when(blogService.publish(1001L, 9001L, "t", "c", "a.jpg")).thenReturn(7001L);

        assertEquals(7001L, c.publish(req).getData());
    }

    @Test
    @DisplayName("BlogController: 点赞参数 like=false 走取消点赞")
    void blogUnlike() {
        BlogController c = new BlogController(blogService, commentService);
        when(blogService.unlike(1001L, 7001L)).thenReturn(true);

        assertTrue(c.like(7001L, false).getData());

        verify(blogService).unlike(1001L, 7001L);
        verify(blogService, never()).like(anyLong(), anyLong());
    }

    @Test
    @DisplayName("BlogController: 评论请求体解包")
    void blogComment() {
        BlogController c = new BlogController(blogService, commentService);
        BlogController.CommentRequest req = new BlogController.CommentRequest();
        req.setParentId(8001L);
        req.setContent("同感");
        when(commentService.add(1001L, 7001L, 8001L, "同感")).thenReturn(9001L);

        assertEquals(9001L, c.addComment(7001L, req).getData());
    }

    @Test
    @DisplayName("FollowController: 关注/取关透传")
    void followForwards() {
        FollowController c = new FollowController(followService);
        when(followService.follow(1001L, 2002L, true)).thenReturn(true);
        when(followService.follow(1001L, 2002L, false)).thenReturn(true);

        assertTrue(c.follow(2002L, true).getData());
        assertTrue(c.follow(2002L, false).getData());

        verify(followService, times(2)).follow(eq(1001L), eq(2002L), anyBoolean());
    }

    @Test
    @DisplayName("SignController: 签到结果封装为 Map")
    void signWrapsResult() {
        SignController c = new SignController(signService, pointService);
        when(signService.sign(1001L)).thenReturn(new SignService.SignResult(true, 3, 6, 20));

        Map<String, Object> m = c.sign().getData();

        assertEquals(true, m.get("fresh"));
        assertEquals(3, m.get("continuousDays"));
        assertEquals(6, m.get("points"));
        assertEquals(20, m.get("balance"));
    }

    @Test
    @DisplayName("ShopTypeController: CONSUMER 写分类 -> 403")
    void typeRejectsConsumer() {
        ShopTypeController c = new ShopTypeController(typeService);
        UserHolder.setRole("CONSUMER");
        ShopTypeController.TypeRequest req = new ShopTypeController.TypeRequest();
        req.setName("x");

        BaseException ex = assertThrows(BaseException.class, () -> c.create(req));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verifyNoInteractions(typeService);
    }

    @Test
    @DisplayName("ShopTypeController: MERCHANT 可写分类")
    void typeAllowsMerchant() {
        ShopTypeController c = new ShopTypeController(typeService);
        UserHolder.setRole("MERCHANT");
        ShopTypeController.TypeRequest req = new ShopTypeController.TypeRequest();
        req.setName("推拿");
        when(typeService.create("推拿", null, null)).thenReturn(1L);

        assertEquals(1L, c.create(req).getData());
    }

    @Test
    @DisplayName("FeedController: 透传分页参数")
    void feedForwards() {
        FeedController c = new FeedController(feedService);
        when(feedService.timeline(1001L, 1, 10)).thenReturn(PageResult.empty(1, 10));

        assertNotNull(c.timeline(1, 10).getData());

        verify(feedService).timeline(1001L, 1, 10);
    }
}
