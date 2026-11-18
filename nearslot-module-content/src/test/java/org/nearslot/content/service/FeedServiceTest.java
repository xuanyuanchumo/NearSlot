package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.content.entity.Blog;
import org.nearslot.content.mapper.BlogMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Feed 单测（FR-8 / ADR-027）。
 *
 * 锁住本项目对参考项目最关键的改进：**大V 不写扩散**。
 * hmdp-plus 对每个粉丝逐个 ZADD，十万粉丝=十万次写；
 * 这里小V 走收件箱、大V 读取时合并，且合并数量有上限。
 */
class FeedServiceTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zsetOps;
    private BlogMapper blogMapper;
    private FollowService followService;
    private BlogService blogService;
    private FeedService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zsetOps = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        blogMapper = mock(BlogMapper.class);
        followService = mock(FollowService.class);
        blogService = mock(BlogService.class);
        service = new FeedService(redis, blogMapper, followService, blogService,
            new org.nearslot.content.config.ContentProperties());
    }

    @Test
    @DisplayName("timeline: 无关注无收件箱 -> 空页")
    void timelineEmpty() {
        when(zsetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        when(followService.following(1001L)).thenReturn(List.of());

        var res = service.timeline(1001L, 1, 10);

        assertEquals(0, res.items().size());
    }

    @Test
    @DisplayName("timeline: 收件箱有内容 -> 返回笔记列表（倒序）")
    void timelineFromInbox() {
        when(zsetOps.reverseRange(anyString(), anyLong(), anyLong()))
            .thenReturn(new LinkedHashSet<>(List.of("7002", "7001")));
        when(followService.following(1001L)).thenReturn(List.of());
        Blog b1 = blog(7001L);
        Blog b2 = blog(7002L);
        when(blogMapper.selectByIds(anyCollection())).thenReturn(List.of(b1, b2));

        var res = service.timeline(1001L, 1, 10);

        assertEquals(2, res.items().size());
        // 新笔记（id 大）在前
        assertEquals("7002", res.items().get(0).get("id"));
    }

    @Test
    @DisplayName("timeline: 关注大V -> 读取时合并其最新笔记（不写扩散）")
    void timelineMergesBigV() {
        when(zsetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        when(followService.following(1001L)).thenReturn(List.of(2002L));
        // 2002 粉丝数超阈值 -> 大V
        when(blogService.followerCountOf(2002L)).thenReturn(50000);
        when(blogMapper.selectList(any())).thenReturn(List.of(blog(8001L)));
        when(blogMapper.selectByIds(anyCollection())).thenReturn(List.of(blog(8001L)));

        var res = service.timeline(1001L, 1, 10);

        assertEquals(1, res.items().size());
        // 关键：大V 的内容是读时才查的（selectList 被调用），而不是发帖时推送
        verify(blogMapper).selectList(any());
    }

    @Test
    @DisplayName("timeline: 关注小V -> 不读取时合并（内容在收件箱里）")
    void timelineSkipsSmallV() {
        when(zsetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());
        when(followService.following(1001L)).thenReturn(List.of(3003L));
        when(blogService.followerCountOf(3003L)).thenReturn(10);   // 小V

        service.timeline(1001L, 1, 10);

        // 小V 不触发实时合并查询
        verify(blogMapper, never()).selectList(any());
    }

    private Blog blog(long id) {
        Blog b = new Blog();
        b.setId(id);
        b.setUserId(4004L);
        b.setTitle("标题");
        b.setContent("内容");
        b.setLiked(1);
        b.setComments(0);
        return b;
    }
}
