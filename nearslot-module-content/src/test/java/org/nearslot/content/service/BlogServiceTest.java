package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.api.CompletedOrderQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.Blog;
import org.nearslot.content.mapper.BlogMapper;
import org.nearslot.content.mapper.UserFollowMapper;
import org.nearslot.content.mapper.UserSocialStatMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 探店笔记单测（FR-7 / ADR-026）。
 *
 * 核心锁住"履约驱动"这条本项目差异化规则：
 * **没有已完成订单就不能发笔记** —— 参考项目允许无订单发帖，内容与消费脱钩。
 */
class BlogServiceTest {

    private BlogMapper blogMapper;
    private UserSocialStatMapper statMapper;
    private UserFollowMapper followMapper;
    private CompletedOrderQuery completedOrderQuery;
    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private BlogService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        blogMapper = mock(BlogMapper.class);
        statMapper = mock(UserSocialStatMapper.class);
        followMapper = mock(UserFollowMapper.class);
        completedOrderQuery = mock(CompletedOrderQuery.class);
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        service = new BlogService(blogMapper, statMapper, followMapper, completedOrderQuery, redis,
            new org.nearslot.content.config.ContentProperties());
    }

    @Test
    @DisplayName("publish: 订单未完成 -> 403（履约驱动，防无消费刷内容）")
    void publishRejectsUncompletedOrder() {
        when(completedOrderQuery.isCompletedBy(1001L, 9001L)).thenReturn(false);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.publish(1001L, 9001L, "标题", "内容", ""));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
        verify(blogMapper, never()).insert(any(Blog.class));
    }

    @Test
    @DisplayName("publish: 订单已完成 -> 落库并带 shopId")
    void publishAcceptsCompletedOrder() {
        when(completedOrderQuery.isCompletedBy(1001L, 9001L)).thenReturn(true);
        when(completedOrderQuery.shopIdOf(9001L)).thenReturn(5001L);
        when(statMapper.selectById(1001L)).thenReturn(new org.nearslot.content.entity.UserSocialStat());

        Long id = service.publish(1001L, 9001L, "很棒", "服务专业", "a.jpg,b.jpg");

        assertNotNull(id);
        verify(blogMapper).insert(argThat((Blog b) ->
            b.getUserId().equals(1001L)
                && b.getOrderId().equals(9001L)
                && b.getShopId().equals(5001L)
                && b.getLiked() == 0
                && b.getStatus() == 1));
    }

    @Test
    @DisplayName("publish: 内容为空 -> 40001")
    void publishRejectsBlankContent() {
        BaseException ex = assertThrows(BaseException.class,
            () -> service.publish(1001L, 9001L, "", "  ", ""));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("publish: 同一订单重复发笔记 -> 40001（一单一笔记）")
    void publishRejectsDuplicateOrder() {
        when(completedOrderQuery.isCompletedBy(1001L, 9001L)).thenReturn(true);
        when(completedOrderQuery.shopIdOf(9001L)).thenReturn(5001L);
        when(statMapper.selectById(1001L)).thenReturn(new org.nearslot.content.entity.UserSocialStat());
        when(blogMapper.insert(any(Blog.class)))
            .thenThrow(new DuplicateKeyException("uk_blog_order"));

        BaseException ex = assertThrows(BaseException.class,
            () -> service.publish(1001L, 9001L, "t", "c", ""));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("like: 首次点赞 -> 计数 +1")
    void likeFirstTime() {
        Blog b = new Blog();
        b.setId(7001L);
        b.setUserId(2002L);
        when(blogMapper.selectById(7001L)).thenReturn(b);
        when(setOps.add(anyString(), anyString())).thenReturn(1L);

        assertTrue(service.like(1001L, 7001L));

        verify(blogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("like: 重复点赞 -> 返回 false，不重复计数（幂等）")
    void likeIdempotent() {
        when(blogMapper.selectById(7001L)).thenReturn(new Blog() {{ setId(7001L); setUserId(2002L); }});
        when(setOps.add(anyString(), anyString())).thenReturn(0L);

        assertFalse(service.like(1001L, 7001L));

        verify(blogMapper, never()).update(any(), any());
    }

    // ---------------- 查询与视图 ----------------

    private Blog sampleBlog() {
        Blog b = new Blog();
        b.setId(7001L);
        b.setUserId(2002L);
        b.setShopId(5001L);
        b.setTitle("很赞");
        b.setContent("服务专业");
        b.setImages("a.jpg,b.jpg");
        b.setLiked(3);
        b.setComments(1);
        b.setStatus(1);
        return b;
    }

    @Test
    @DisplayName("detail: 存在 -> 返回视图含图片列表与点赞态")
    void detailReturnsView() {
        when(blogMapper.selectById(7001L)).thenReturn(sampleBlog());
        when(setOps.isMember(anyString(), anyString())).thenReturn(true);

        Map<String, Object> v = service.detail(1001L, 7001L);

        assertEquals("7001", v.get("id"));
        assertEquals("很赞", v.get("title"));
        assertEquals(List.of("a.jpg", "b.jpg"), v.get("images"));
        assertEquals(true, v.get("isLiked"));
        assertEquals(3, v.get("liked"));
    }

    @Test
    @DisplayName("detail: 已隐藏 -> 40401")
    void detailHidden() {
        Blog b = sampleBlog();
        b.setStatus(2);
        when(blogMapper.selectById(7001L)).thenReturn(b);

        BaseException ex = assertThrows(BaseException.class, () -> service.detail(1001L, 7001L));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("detail: 无图片 -> 空列表而不是 [\"\"]")
    void detailNoImages() {
        Blog b = sampleBlog();
        b.setImages("");
        when(blogMapper.selectById(7001L)).thenReturn(b);

        assertEquals(List.of(), service.detail(1001L, 7001L).get("images"));
    }

    @Test
    @DisplayName("hot: 返回分页视图并带点赞态")
    void hotReturnsPage() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Blog> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        p.setRecords(List.of(sampleBlog()));
        p.setTotal(1);
        when(blogMapper.selectPage(any(), any())).thenReturn(p);

        var res = service.hot(1001L, 1, 10);

        assertEquals(1, res.items().size());
        assertEquals("7001", res.items().get(0).get("id"));
    }

    @Test
    @DisplayName("mine / byShop: 均走分页查询")
    void mineAndByShop() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Blog> p =
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        p.setRecords(List.of(sampleBlog()));
        p.setTotal(1);
        when(blogMapper.selectPage(any(), any())).thenReturn(p);

        assertEquals(1, service.mine(1001L, 1, 10).items().size());
        assertEquals(1, service.byShop(1001L, 5001L, 1, 10).items().size());
    }

    @Test
    @DisplayName("unlike: 取消点赞 -> 计数 -1（GREATEST 防负）")
    void unlikeWorks() {
        when(blogMapper.selectById(7001L)).thenReturn(sampleBlog());
        when(setOps.remove(anyString(), anyString())).thenReturn(1L);

        assertTrue(service.unlike(1001L, 7001L));

        verify(blogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("unlike: 未点赞 -> false，不改计数")
    void unlikeNotLiked() {
        when(blogMapper.selectById(7001L)).thenReturn(sampleBlog());
        when(setOps.remove(anyString(), anyString())).thenReturn(0L);

        assertFalse(service.unlike(1001L, 7001L));

        verify(blogMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("followerCountOf: 无统计记录 -> 0")
    void followerCountMissing() {
        when(statMapper.selectById(2002L)).thenReturn(null);
        assertEquals(0, service.followerCountOf(2002L));
    }

    @Test
    @DisplayName("publish: 首次发布自动建统计行")
    void publishCreatesStat() {
        when(completedOrderQuery.isCompletedBy(1001L, 9001L)).thenReturn(true);
        when(completedOrderQuery.shopIdOf(9001L)).thenReturn(5001L);
        when(statMapper.selectById(1001L)).thenReturn(null);   // 无统计行

        service.publish(1001L, 9001L, "t", "c", "");

        verify(statMapper).insert(any(org.nearslot.content.entity.UserSocialStat.class));
    }

    @Test
    @DisplayName("like: 笔记不存在 -> 40401")
    void likeMissingBlog() {
        when(blogMapper.selectById(7001L)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.like(1001L, 7001L));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }
}
