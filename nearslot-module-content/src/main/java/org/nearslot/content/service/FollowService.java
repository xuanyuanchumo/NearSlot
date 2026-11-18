package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.UserFollow;
import org.nearslot.content.entity.UserSocialStat;
import org.nearslot.content.mapper.UserFollowMapper;
import org.nearslot.content.mapper.UserSocialStatMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 关注关系（FR-8 / ADR-028）。
 *
 * **本项目化适配**：
 * 1. **双向缓存**：既缓存"我关注了谁"（follow:following:{uid}），
 *    也缓存"谁关注了我"（followers:{uid}）—— 后者供 Feed 推送直接取用，
 *    避免每次发帖都扫关系表（hmdp-plus 每次发帖都 `select * from tb_follow`）。
 * 2. **冗余计数**：following_count / follower_count 原子增减，禁止 COUNT。
 *    两个参考项目都没有这两个字段，前端只能 COUNT，数据量大必然拖垮列表。
 * 3. **幂等**：唯一键 uk_follow 兜底，重复关注转成幂等成功而非 500。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final UserFollowMapper followMapper;
    private final UserSocialStatMapper statMapper;
    private final StringRedisTemplate redis;
    /** 关系缓存 TTL 外置到 `nearslot.content.relation-cache-hours`。 */
    private final org.nearslot.content.config.ContentProperties props;

    /**
     * 关注 / 取关。
     *
     * @param isFollow true=关注 false=取关
     * @return true=状态发生变化 false=本来就是该状态（幂等）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean follow(Long userId, Long targetUserId, boolean isFollow) {
        if (userId == null || targetUserId == null) {
            throw new BaseException(BaseCode.BAD_REQUEST, "用户参数不能为空");
        }
        if (userId.equals(targetUserId)) {
            throw new BaseException(BaseCode.BAD_REQUEST, "不能关注自己");
        }
        if (isFollow) {
            return doFollow(userId, targetUserId);
        }
        return doUnfollow(userId, targetUserId);
    }

    private boolean doFollow(Long userId, Long targetUserId) {
        UserFollow f = new UserFollow();
        f.setId(IdGenerator.nextId());
        f.setUserId(userId);
        f.setFollowUserId(targetUserId);
        f.setCreatedAt(java.time.LocalDateTime.now());
        try {
            followMapper.insert(f);
        } catch (DuplicateKeyException e) {
            return false;   // 已关注，幂等
        }
        ensureStat(userId);
        ensureStat(targetUserId);
        // 双向冗余计数
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", userId).setSql("following_count = following_count + 1"));
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", targetUserId).setSql("follower_count = follower_count + 1"));
        // 双向缓存
        cacheAdd(RedisConstants.PREFIX + "following:" + userId, targetUserId);
        cacheAdd(RedisConstants.PREFIX + "followers:" + targetUserId, userId);
        log.info("关注成功 userId={} -> {}", userId, targetUserId);
        return true;
    }

    private boolean doUnfollow(Long userId, Long targetUserId) {
        int removed = followMapper.delete(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserFollow>lambdaQuery()
                .eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowUserId, targetUserId));
        if (removed == 0) return false;
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", userId).setSql("following_count = GREATEST(following_count - 1, 0)"));
        statMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UserSocialStat>()
                .eq("user_id", targetUserId).setSql("follower_count = GREATEST(follower_count - 1, 0)"));
        cacheRemove(RedisConstants.PREFIX + "following:" + userId, targetUserId);
        cacheRemove(RedisConstants.PREFIX + "followers:" + targetUserId, userId);
        log.info("取消关注 userId={} -> {}", userId, targetUserId);
        return true;
    }

    /** 是否已关注（先缓存后 DB）。 */
    public boolean isFollowing(Long userId, Long targetUserId) {
        if (userId == null || targetUserId == null) return false;
        String key = RedisConstants.PREFIX + "following:" + userId;
        Boolean cached = redis.opsForSet().isMember(key, String.valueOf(targetUserId));
        if (Boolean.TRUE.equals(cached)) return true;
        // 缓存未命中回源（可能是缓存过期），命中则补写
        Long cnt = followMapper.selectCount(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserFollow>lambdaQuery()
                .eq(UserFollow::getUserId, userId)
                .eq(UserFollow::getFollowUserId, targetUserId));
        if (cnt != null && cnt > 0) {
            cacheAdd(key, targetUserId);
            return true;
        }
        return false;
    }

    /** 我的关注列表。 */
    public List<Long> following(Long userId) {
        Set<String> ids = redis.opsForSet().members(RedisConstants.PREFIX + "following:" + userId);
        if (ids != null && !ids.isEmpty()) {
            return ids.stream().map(Long::valueOf).toList();
        }
        return followMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserFollow>lambdaQuery()
                    .eq(UserFollow::getUserId, userId))
            .stream().map(UserFollow::getFollowUserId).toList();
    }

    /** 我的粉丝列表。 */
    public List<Long> followers(Long userId) {
        Set<String> ids = redis.opsForSet().members(RedisConstants.PREFIX + "followers:" + userId);
        if (ids != null && !ids.isEmpty()) {
            return ids.stream().map(Long::valueOf).toList();
        }
        return followMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<UserFollow>lambdaQuery()
                    .eq(UserFollow::getFollowUserId, userId))
            .stream().map(UserFollow::getUserId).toList();
    }

    /** 关注/粉丝数（读冗余计数，不 COUNT）。 */
    public Map<String, Object> statOf(Long userId) {
        UserSocialStat s = statMapper.selectById(userId);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("userId", String.valueOf(userId));
        m.put("followingCount", s == null || s.getFollowingCount() == null ? 0 : s.getFollowingCount());
        m.put("followerCount", s == null || s.getFollowerCount() == null ? 0 : s.getFollowerCount());
        m.put("blogCount", s == null || s.getBlogCount() == null ? 0 : s.getBlogCount());
        m.put("likedReceived", s == null || s.getLikedReceived() == null ? 0 : s.getLikedReceived());
        return m;
    }

    private void cacheAdd(String key, Long member) {
        redis.opsForSet().add(key, String.valueOf(member));
        redis.expire(key, props.getRelationCacheHours(), TimeUnit.HOURS);
    }

    private void cacheRemove(String key, Long member) {
        redis.opsForSet().remove(key, String.valueOf(member));
    }

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
