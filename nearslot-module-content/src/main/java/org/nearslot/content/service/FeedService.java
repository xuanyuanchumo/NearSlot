package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.entity.Blog;
import org.nearslot.content.mapper.BlogMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feed 流（FR-8 / ADR-027 拉模式）。
 *
 * **为什么不照抄 hmdp-plus 的写扩散**：
 * 它的 saveBlog 对每个粉丝逐个 ZADD，粉丝 10 万就是 10 万次 Redis 写。
 * NearSlot 采用混合模式：
 *   · 小 V（粉丝 ≤ 1000）：发帖时写进粉丝收件箱（读时零成本）；
 *   · 大 V：发帖不扩散，读 Feed 时实时合并其最新笔记。
 *
 * 这样既保留"关注了马上能看到"的体验，又把单次发帖的写放大限制在 1000 次以内。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final StringRedisTemplate redis;
    private final BlogMapper blogMapper;
    private final FollowService followService;
    private final BlogService blogService;
    /** 内容域策略（大 V 阈值、合并上限、作者取样数）外置到 `nearslot.content.*`。 */
    private final org.nearslot.content.config.ContentProperties props;

    /**
     * 读关注流。
     *
     * @param userId 当前用户
     * @param page   页码（拉模式按游标语义简化为"取前 N 条"，深分页走 hot）
     */
    public PageResult<Map<String, Object>> timeline(Long userId, long page, long size) {
        int fetch = (int) Math.min(size * page, props.getFeedMaxFetch());

        // 1) 收件箱：小 V 发帖时已写入的 ZSet（按时间倒序）
        Set<String> inboxIds = redis.opsForZSet()
            .reverseRange(RedisConstants.PREFIX + "feed:" + userId, 0, fetch - 1);

        // 2) 大 V 的最新笔记：读取时合并（ADR-027）
        Set<Long> merged = new LinkedHashSet<>();
        if (inboxIds != null) {
            inboxIds.forEach(id -> merged.add(Long.valueOf(id)));
        }
        int bigVCount = 0;
        for (Long authorId : followService.following(userId)) {
            if (bigVCount >= props.getMaxBigVMerge()) {
                log.debug("大V合并达到上限 {}，本轮跳过其余作者 userId={}",
                    props.getMaxBigVMerge(), userId);
                break;
            }
            if (blogService.followerCountOf(authorId) > props.getBigVThreshold()) {
                bigVCount++;
                blogMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.<Blog>lambdaQuery()
                            .eq(Blog::getUserId, authorId)
                            .eq(Blog::getStatus, 1)
                            .orderByDesc(Blog::getId)
                            .last("LIMIT " + props.getFeedBlogsPerAuthor()))
                    .forEach(b -> merged.add(b.getId()));
            }
        }

        if (merged.isEmpty()) {
            return PageResult.empty(page, size);
        }
        List<Long> ids = new ArrayList<>(merged);
        // 注意：MyBatis-Plus 的 selectByIds 可能返回**不可变列表**，
        // 直接 sort() 会抛 UnsupportedOperationException（单测已复现）。
        // 这里包一层可变副本，避免运行时炸在 Feed 首页这种高频路径上。
        List<Blog> blogs = new ArrayList<>(blogMapper.selectByIds(ids));
        // 按 id 倒序（新笔记在前）
        blogs.sort((a, b) -> Long.compare(b.getId(), a.getId()));

        int from = (int) Math.min((page - 1) * size, blogs.size());
        int to = (int) Math.min(from + size, blogs.size());
        List<Map<String, Object>> items = blogs.subList(from, to).stream()
            .map(b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", String.valueOf(b.getId()));
                m.put("userId", String.valueOf(b.getUserId()));
                m.put("title", b.getTitle());
                m.put("content", b.getContent());
                m.put("liked", b.getLiked());
                m.put("comments", b.getComments());
                m.put("isLiked", blogService.isLiked(userId, b.getId()));
                m.put("createdAt", b.getCreatedAt());
                return m;
            }).toList();
        return PageResult.ofPage(items, blogs.size(), page, size,
            (blogs.size() + size - 1) / Math.max(size, 1));
    }
}
