package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.UserFollow;
import org.nearslot.content.entity.UserSocialStat;
import org.nearslot.content.mapper.UserFollowMapper;
import org.nearslot.content.mapper.UserSocialStatMapper;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 关注关系单测（FR-8 / ADR-028）。
 *
 * 锁住"计数与关系分离"：计数走冗余列原子增减，**不得 COUNT**；
 * 关注/取关都必须双向维护计数与缓存。
 */
class FollowServiceTest {

    private UserFollowMapper followMapper;
    private UserSocialStatMapper statMapper;
    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private FollowService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        followMapper = mock(UserFollowMapper.class);
        statMapper = mock(UserSocialStatMapper.class);
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        service = new FollowService(followMapper, statMapper, redis,
            new org.nearslot.content.config.ContentProperties());
    }

    @Test
    @DisplayName("follow: 不能关注自己 -> 40001")
    void cannotFollowSelf() {
        BaseException ex = assertThrows(BaseException.class,
            () -> service.follow(1001L, 1001L, true));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("follow: 首次关注 -> 关系落库 + 双向计数 +1 + 双向缓存")
    void followFirstTime() {
        when(statMapper.selectById(anyLong())).thenReturn(new UserSocialStat());
        doReturn(1).when(followMapper).insert(any(UserFollow.class));

        assertTrue(service.follow(1001L, 2002L, true));

        verify(followMapper).insert(any(UserFollow.class));
        // 两个方向的计数更新（关注数 / 粉丝数）
        verify(statMapper, times(2)).update(isNull(), any());
        // 两个方向的缓存
        verify(setOps, times(2)).add(anyString(), anyString());
        // 关键：绝不能用 selectCount 统计（ADR-028）
        verify(followMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("follow: 重复关注 -> 返回 false（幂等），不重复加计数")
    void followIdempotent() {
        when(followMapper.insert(any(UserFollow.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_follow"));

        assertFalse(service.follow(1001L, 2002L, true));

        verify(statMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("unfollow: 未关注 -> false，不改计数")
    void unfollowNotFollowing() {
        when(followMapper.delete(any())).thenReturn(0);

        assertFalse(service.follow(1001L, 2002L, false));

        verify(statMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("unfollow: 已关注 -> 删关系 + 双向计数 -1（GREATEST 防负）")
    void unfollowWorks() {
        when(followMapper.delete(any())).thenReturn(1);

        assertTrue(service.follow(1001L, 2002L, false));

        verify(followMapper).delete(any());
        verify(statMapper, times(2)).update(isNull(), any());
    }

    @Test
    @DisplayName("isFollowing: 缓存命中直接返回 true（不查 DB）")
    void isFollowingFromCache() {
        when(setOps.isMember(anyString(), anyString())).thenReturn(true);

        assertTrue(service.isFollowing(1001L, 2002L));

        verify(followMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("statOf: 读冗余计数，不 COUNT 关系表")
    void statReadsRedundantCounter() {
        UserSocialStat s = new UserSocialStat();
        s.setUserId(1001L);
        s.setFollowingCount(5);
        s.setFollowerCount(8);
        s.setBlogCount(3);
        s.setLikedReceived(20);
        when(statMapper.selectById(1001L)).thenReturn(s);

        var m = service.statOf(1001L);

        assertEquals(5, m.get("followingCount"));
        assertEquals(8, m.get("followerCount"));
        assertEquals(3, m.get("blogCount"));
        verify(followMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("statOf: 无记录 -> 全 0，不抛异常")
    void statMissing() {
        when(statMapper.selectById(1001L)).thenReturn(null);

        assertEquals(0, service.statOf(1001L).get("followerCount"));
    }
}
