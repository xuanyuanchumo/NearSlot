package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** FR-4.3 热门技师榜单测。 */
class TechRankServiceTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zsetOps;
    private ValueOperations<String, String> valueOps;
    private TechRankService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zsetOps = mock(ZSetOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new TechRankService(redis);
    }

    @Test
    @DisplayName("支付成功 -> ZINCRBY +1 且设置 90 天 TTL")
    void recordDeal_incrementsAndSetsTtl() {
        LocalDate date = LocalDate.of(2026, 11, 17);

        service.recordDeal(1L, 2001L, date);

        String expectedKey = RedisConstants.TECH_RANK + "1:20261117";
        verify(zsetOps).incrementScore(expectedKey, "2001", 1.0);
        verify(redis).expire(eq(expectedKey), eq(90L), any());
    }

    @Test
    @DisplayName("查询 Top N 返回排名与成交数")
    void top_returnsRankedRows() {
        LocalDate date = LocalDate.of(2026, 11, 17);
        ZSetOperations.TypedTuple<String> t1 = mock(ZSetOperations.TypedTuple.class);
        when(t1.getValue()).thenReturn("2001");
        when(t1.getScore()).thenReturn(5.0);
        ZSetOperations.TypedTuple<String> t2 = mock(ZSetOperations.TypedTuple.class);
        when(t2.getValue()).thenReturn("2002");
        when(t2.getScore()).thenReturn(3.0);
        when(zsetOps.reverseRangeWithScores(anyString(), eq(0L), eq(9L)))
            .thenReturn(new LinkedHashSet<>(java.util.List.of(t1, t2)));

        var rows = service.top(1L, date, 10);

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).get("rank"));
        assertEquals("2001", rows.get(0).get("technicianId"));
        assertEquals(5, rows.get(0).get("deals"));
        assertEquals(2, rows.get(1).get("rank"));
    }

    @Test
    @DisplayName("空榜单返回空列表")
    void top_emptyQueue() {
        when(zsetOps.reverseRangeWithScores(anyString(), eq(0L), eq(4L))).thenReturn(Set.of());
        assertTrue(service.top(1L, LocalDate.now(), 5).isEmpty());
    }

    @Test
    @DisplayName("参数为空时不写入")
    void recordDeal_nullSafe() {
        service.recordDeal(null, 2001L, LocalDate.now());
        verify(zsetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }
}
