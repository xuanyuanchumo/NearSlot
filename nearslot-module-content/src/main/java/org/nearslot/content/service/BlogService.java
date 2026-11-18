package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.api.CompletedOrderQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.entity.Blog;
import org.nearslot.content.entity.UserSocialStat;
import org.nearslot.content.mapper.BlogMapper;
import org.nearslot.content.mapper.UserSocialStatMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 探店笔记（FR-7）。
 *
 * **本项目化适配（ADR-026 / ADR-028）**：
 * 1. **履约驱动**：发布必须绑定一个**已完成**订单（经 common 契约
 *    {@link CompletedOrderQuery} 校验，不直接依赖 appointment 模块）。
 *    参考项目允许无订单发帖，内容与消费脱钩，无法防刷。
 * 2. **计数与关系分离**：liked/comments 走冗余列原子增减；
 *    "我是否点过赞"用 Redis Set，配合 CacheManager 可本地化，避免热点 key 读放大。
 * 3. **一单一笔记**：uk_blog_order 兜底，同一履约只能产出一篇（防刷内容）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogService {

    /** 点击数点赞关系集 key 前缀。 */
    private static final String LIKE_KEY = RedisConstants.PREFIX + "blog:like:";
    private final BlogMapper blogMapper;
    private final UserSocialStatMapper statMapper;
    private final org.nearslot.content.mapper.UserFollowMapper followMapper;
    private final CompletedOrderQuery completedOrderQuery;
    private final StringRedisTemplate redis;
    /** 内容域策略（大 V 阈值、缓存 TTL 等）外置到 `nearslot.content.*`。 */
    private final org.nearslot.content.config.ContentProperties props;

    /**
     * 发布笔记（履约驱动）。
     *
     * @param orderId 已完成订单号（ADR-026 硬性要求）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Long userId, Long orderId, String title, String content, String images) {
        if (content == null || content.isBlank()) {
            throw new BaseException(BaseCode.BAD_REQUEST, "笔记内容不能为空");
        }
        // ADR-026：只有本人已完成订单才能发笔记
        if (!completedOrderQuery.isCompletedBy(userId, orderId)) {
            throw new BaseException(BaseCode.FORBIDDEN,
                "仅本人已完成的预约可以发布笔记（履约驱动，防无消费刷内容）");
        }

        Blog blog = new Blog();
        blog.setId(IdGenerator.nextId());
        blog.setUserId(userId);
        blog.setOrderId(orderId);
        blog.setShopId(completedOrderQuery.shopIdOf(orderId));
        blog.setTitle(title == null ? "" : title);
        blog.setContent(content);
        blog.setImages(images == null ? "" : images);
        blog.setLiked(0);
        blog.setComments(0);
        blog.setStatus(1);
        try {
            blogMapper.insert(blog);
        } catch (DuplicateKeyException e) {
            // 一单一笔记：重复发布转成业务错误而非 500
            throw new BaseException(BaseCode.BAD_REQUEST, "该订单已发布过笔记");
        }

        // 作者笔记数 +1（冗余计数，不 COUNT）
        ensureStat(userId);
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", userId)
                .setSql("blog_count = blog_count + 1"));

        pushFeedToFollowers(userId, blog.getId());
        log.info("笔记已发布 blogId={} userId={} orderId={}", blog.getId(), userId, orderId);
        return blog.getId();
    }

    /**
     * Feed 推送（ADR-027 拉模式）。
     *
     * **只推给小 V 收件箱**：作者粉丝数超过阈值时不写扩散，
     * 改由读取端合并。hmdp-plus 对每个粉丝逐个 ZADD，
     * 十万粉丝就是十万次写 —— 这是它最严重的性能缺陷。
     */
    private void pushFeedToFollowers(Long authorId, Long blogId) {
        int followerCount = followerCountOf(authorId);
        int bigVThreshold = props.getBigVThreshold();
        if (followerCount > bigVThreshold) {
            log.info("作者为大V(followers={})，跳过写扩散，读取端合并 authorId={}", followerCount, authorId);
            return;
        }
        // 只取前 N 个粉丝推送，避免单篇笔记无限放大（超过阈值的部分由读取端兜底）
        List<String> followers = followersOf(authorId, bigVThreshold);
        long now = System.currentTimeMillis();
        for (String followerId : followers) {
            redis.opsForZSet().add(RedisConstants.PREFIX + "feed:" + followerId,
                String.valueOf(blogId), now);
            redis.expire(RedisConstants.PREFIX + "feed:" + followerId, 7, TimeUnit.DAYS);
        }
        log.info("Feed 已推送 blogId={} 收件人数={}（大V阈值 {}）", blogId, followers.size(), bigVThreshold);
    }

    /** 点赞（幂等：重复点赞不重复计数）。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean like(Long userId, Long blogId) {
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) throw new BaseException(BaseCode.NOT_FOUND, "笔记不存在");
        // 用 Set 判重而不是查 DB：点赞是高并发读+写混合场景
        Long added = redis.opsForSet().add(LIKE_KEY + blogId, String.valueOf(userId));
        if (added == null || added == 0) {
            return false;   // 已点过，幂等返回
        }
        blogMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Blog>()
                .eq("id", blogId)
                .setSql("liked = liked + 1"));
        // 作者获赞数 +1
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", blog.getUserId())
                .setSql("liked_received = liked_received + 1"));
        return true;
    }

    /** 取消点赞。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean unlike(Long userId, Long blogId) {
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) throw new BaseException(BaseCode.NOT_FOUND, "笔记不存在");
        Long removed = redis.opsForSet().remove(LIKE_KEY + blogId, String.valueOf(userId));
        if (removed == null || removed == 0) return false;
        // 用 GREATEST 防负数（并发取消可能重复减）
        blogMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Blog>()
                .eq("id", blogId)
                .setSql("liked = GREATEST(liked - 1, 0)"));
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", blog.getUserId())
                .setSql("liked_received = GREATEST(liked_received - 1, 0)"));
        return true;
    }

    /** 是否已点赞（读 Redis Set，不查 DB）。 */
    public boolean isLiked(Long userId, Long blogId) {
        if (userId == null) return false;
        return Boolean.TRUE.equals(
            redis.opsForSet().isMember(LIKE_KEY + blogId, String.valueOf(userId)));
    }

    /** 笔记详情（含点赞态）。 */
    public Map<String, Object> detail(Long userId, Long blogId) {
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null || blog.getStatus() == null || blog.getStatus() != 1) {
            throw new BaseException(BaseCode.NOT_FOUND, "笔记不存在");
        }
        return toView(blog, isLiked(userId, blogId));
    }

    /** 热门笔记（按冗余列 liked 排序，禁止 COUNT/聚合）。 */
    public PageResult<Map<String, Object>> hot(Long userId, long page, long size) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Blog>lambdaQuery()
            .eq(Blog::getStatus, 1)
            .orderByDesc(Blog::getLiked)
            .orderByDesc(Blog::getId);
        var p = blogMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return PageResult.ofPage(
            p.getRecords().stream().map(b -> toView(b, isLiked(userId, b.getId()))).toList(),
            p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 我的笔记。 */
    public PageResult<Map<String, Object>> mine(Long userId, long page, long size) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Blog>lambdaQuery()
            .eq(Blog::getUserId, userId)
            .orderByDesc(Blog::getId);
        var p = blogMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return PageResult.ofPage(
            p.getRecords().stream().map(b -> toView(b, isLiked(userId, b.getId()))).toList(),
            p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /** 按店铺查笔记（店铺详情页"用户评价"入口）。 */
    public PageResult<Map<String, Object>> byShop(Long userId, Long shopId, long page, long size) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<Blog>lambdaQuery()
            .eq(Blog::getShopId, shopId)
            .eq(Blog::getStatus, 1)
            .orderByDesc(Blog::getId);
        var p = blogMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return PageResult.ofPage(
            p.getRecords().stream().map(b -> toView(b, isLiked(userId, b.getId()))).toList(),
            p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    private Map<String, Object> toView(Blog b, boolean liked) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", String.valueOf(b.getId()));
        m.put("userId", String.valueOf(b.getUserId()));
        m.put("shopId", b.getShopId() == null ? null : String.valueOf(b.getShopId()));
        m.put("title", b.getTitle());
        m.put("content", b.getContent());
        m.put("images", b.getImages() == null || b.getImages().isBlank()
            ? List.of() : List.of(b.getImages().split(",")));
        m.put("liked", b.getLiked());
        m.put("comments", b.getComments());
        m.put("isLiked", liked);
        m.put("createdAt", b.getCreatedAt());
        return m;
    }

    /** 粉丝数（读冗余计数表，不 COUNT）。 */
    public int followerCountOf(Long userId) {
        UserSocialStat stat = statMapper.selectById(userId);
        return stat == null || stat.getFollowerCount() == null ? 0 : stat.getFollowerCount();
    }

    /**
     * 取粉丝 ID 列表。
     *
     * 优先读 Redis Set（FollowService 关注时维护），为空则回源关系表并重建缓存 ——
     * 缓存丢失不能让推送静默失败（参考项目的写扩散若 Redis 挂了就丢 Feed）。
     */
    private List<String> followersOf(Long authorId, int limit) {
        String key = RedisConstants.PREFIX + "followers:" + authorId;
        var cached = redis.opsForSet().members(key);
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().limit(limit).toList();
        }
        var rows = followMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<org.nearslot.content.entity.UserFollow>lambdaQuery()
                .eq(org.nearslot.content.entity.UserFollow::getFollowUserId, authorId)
                .last("LIMIT " + limit));
        if (rows.isEmpty()) return List.of();
        String[] ids = rows.stream()
            .map(r -> String.valueOf(r.getUserId())).toArray(String[]::new);
        redis.opsForSet().add(key, ids);
        redis.expire(key, props.getRelationCacheHours(), TimeUnit.HOURS);
        return List.of(ids);
    }

    /** 确保统计行存在（存在则不动，避免重复 insert 撞主键）。 */
    private void ensureStat(Long userId) {
        if (statMapper.selectById(userId) != null) return;
        UserSocialStat stat = new UserSocialStat();
        stat.setUserId(userId);
        stat.setFollowingCount(0);
        stat.setFollowerCount(0);
        stat.setBlogCount(0);
        stat.setLikedReceived(0);
        try {
            statMapper.insert(stat);
        } catch (DuplicateKeyException ignored) {
            // 并发首次写入：已存在即可
        }
    }
}
