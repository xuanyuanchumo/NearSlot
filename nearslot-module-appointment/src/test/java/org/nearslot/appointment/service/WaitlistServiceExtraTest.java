package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 候补服务补充单测（leave/rank/size/status 与订阅状态保护）。
 *
 * 重点：已成功的候补不允许退订（保护既有预约语义）。
 */
class WaitlistServiceExtraTest {

    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private ZSetOperations<String, String> zsetOps;
    private HashOperations<String, Object, Object> hashOps;
    private TimeSlotMapper slotMapper;
    private UserLevelQuery levelQuery;
    private WaitlistService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        zsetOps = mock(ZSetOperations.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        when(redis.opsForHash()).thenReturn(hashOps);
        slotMapper = mock(TimeSlotMapper.class);
        levelQuery = uid -> 10;
        TimeSlot s = new TimeSlot();
        s.setId(1L); s.setMinLevel(0);
        when(slotMapper.selectById(anyLong())).thenReturn(s);
        service = new WaitlistService(redis, slotMapper, levelQuery);
    }

    @Test
    @DisplayName("退出候补：状态非 SUCCESS -> 删除并返回 true")
    void leaveNormal() {
        when(hashOps.get(anyString(), any())).thenReturn(String.valueOf(WaitlistService.STATUS_SUBSCRIBED));
        assertTrue(service.leave(1L, 1001L));
        verify(hashOps).delete(RedisConstants.SUBSCRIBE_STATUS + 1L, "1001");
    }

    @Test
    @DisplayName("已成功候补 -> 拒绝退订（保护既有预约语义）")
    void leaveRejectedWhenSucceeded() {
        when(hashOps.get(anyString(), any())).thenReturn(String.valueOf(WaitlistService.STATUS_SUCCESS));
        assertFalse(service.leave(1L, 1001L));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    @DisplayName("退出候补：无状态记录 -> 仍清理并返回 true")
    void leaveWithoutStatus() {
        when(hashOps.get(anyString(), any())).thenReturn(null);
        assertTrue(service.leave(1L, 1001L));
    }

    @Test
    @DisplayName("查询排位：ZSet rank 为 0 基 -> 返回 1 基")
    void rankIsOneBased() {
        when(zsetOps.rank(anyString(), eq("1001"))).thenReturn(2L);
        assertEquals(3L, service.rank(1L, 1001L));
    }

    @Test
    @DisplayName("查询排位：不在队列 -> -1")
    void rankMissing() {
        when(zsetOps.rank(anyString(), anyString())).thenReturn(null);
        assertEquals(-1L, service.rank(1L, 1001L));
    }

    @Test
    @DisplayName("查询队列长度")
    void sizeQuery() {
        when(zsetOps.zCard(anyString())).thenReturn(7L);
        assertEquals(7L, service.size(1L));
    }

    @Test
    @DisplayName("查询状态：有记录返回码")
    void statusFound() {
        when(hashOps.get(anyString(), any())).thenReturn(String.valueOf(WaitlistService.STATUS_SUBSCRIBED));
        assertEquals(WaitlistService.STATUS_SUBSCRIBED, service.status(1L, 1001L));
    }

    @Test
    @DisplayName("查询状态：无记录返回 null")
    void statusMissing() {
        when(hashOps.get(anyString(), any())).thenReturn(null);
        assertNull(service.status(1L, 1001L));
    }

    @Test
    @DisplayName("加入候补：时段不存在 -> NOT_FOUND")
    void joinMissingSlot() {
        when(slotMapper.selectById(anyLong())).thenReturn(null);
        BaseException e = assertThrows(BaseException.class, () -> service.join(99L, 1001L));
        assertEquals(BaseCode.NOT_FOUND, e.getCode());
    }
}
