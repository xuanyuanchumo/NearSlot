package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 签到单测（FR-9 / ADR-029）。
 *
 * 锁住：Lua 原子防并发重复 + 连续签到奖励 + BitMap 日历。
 * 并发两次签到只发一次积分 —— 这是 BitMap 方案必须配 Lua 的原因
 * （先 GETBIT 再 SETBIT 不是原子操作，高并发会重复发分）。
 */
class SignServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private PointService pointService;
    private SignService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        pointService = mock(PointService.class);
        service = new SignService(redis, pointService,
            new org.nearslot.content.config.ContentProperties());
    }

    @Test
    @DisplayName("sign: Lua 返回 1（新签到）-> 发积分并返回连续天数")
    @SuppressWarnings("unchecked")
    void signFirstTime() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        // 今天已签，昨天未签 -> 连续 1 天
        when(valueOps.getBit(anyString(), anyLong())).thenReturn(true).thenReturn(false);
        when(pointService.addPoints(anyLong(), anyInt(), anyString(), anyLong(), anyString()))
            .thenReturn(1);

        SignService.SignResult r = service.sign(1001L);

        assertTrue(r.fresh());
        assertEquals(1, r.continuousDays());
        assertTrue(r.points() >= 1);
        verify(pointService).addPoints(eq(1001L), anyInt(), eq("SIGN"), anyLong(), anyString());
    }

    @Test
    @DisplayName("sign: Lua 返回 0（已签到）-> 不发积分（幂等）")
    @SuppressWarnings("unchecked")
    void signAlreadyDone() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        when(pointService.balanceOf(1001L)).thenReturn(5);

        SignService.SignResult r = service.sign(1001L);

        assertFalse(r.fresh());
        assertEquals(0, r.points());
        assertEquals(5, r.balance());
        verify(pointService, never()).addPoints(anyLong(), anyInt(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("continuousDays: 连续 3 天 -> 返回 3")
    void continuousThreeDays() {
        when(valueOps.getBit(anyString(), anyLong()))
            .thenReturn(true).thenReturn(true).thenReturn(true).thenReturn(false);

        assertEquals(3, service.continuousDays(1001L, LocalDate.now()));
    }

    @Test
    @DisplayName("continuousDays: 今天未签 -> 0")
    void continuousZero() {
        when(valueOps.getBit(anyString(), anyLong())).thenReturn(false);
        assertEquals(0, service.continuousDays(1001L, LocalDate.now()));
    }

    @Test
    @DisplayName("monthSignedDays: 返回当月已签日号")
    void monthSignedDays() {
        // 第 1、2 天签了，其余未签
        when(valueOps.getBit(anyString(), anyLong()))
            .thenReturn(true).thenReturn(true).thenReturn(false).thenReturn(false);

        List<Integer> days = service.monthSignedDays(1001L, LocalDate.now());

        assertTrue(days.contains(1));
        assertTrue(days.contains(2));
    }

    @Test
    @DisplayName("signedToday: 读今日对应 bit")
    void signedTodayReadsBit() {
        when(valueOps.getBit(anyString(), anyLong())).thenReturn(true);
        assertTrue(service.signedToday(1001L));
    }
}
