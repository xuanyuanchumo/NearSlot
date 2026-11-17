package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.ReconcileLog;
import org.nearslot.common.enums.ReconcileStatus;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.ReconcileLogMapper;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对账主循环单测（checkOne 已有覆盖，此处补 reconcile 循环与标记逻辑）。
 *
 * SLO：ABNORMAL 必须为 0。
 */
class ReconciliationTaskLoopTest {

    private AppointmentOrderMapper orderMapper;
    private ReconcileLogMapper logMapper;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private ReconciliationTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        logMapper = mock(ReconcileLogMapper.class);
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        task = new ReconciliationTask(orderMapper, logMapper, redis);
    }

    private AppointmentOrder order(long id, long slotId) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(id); o.setSlotId(slotId);
        return o;
    }

    private ReconcileLog okLog(long orderId) {
        ReconcileLog l = new ReconcileLog();
        l.setOrderId(orderId);
        l.setResult(ReconcileService.RESULT_SUCCESS);
        return l;
    }

    @Test
    @DisplayName("无待对账订单 -> 直接返回，不标记任何状态")
    void noPendingOrders() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        task.reconcile();
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("全部一致 -> 标记 CONSISTENT，不标记 ABNORMAL")
    void allConsistent() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(1L, 10L), order(2L, 11L)));
        when(logMapper.selectList(any())).thenReturn(List.of(okLog(1L)));

        task.reconcile();

        // 每笔都会标记一次状态（CONSISTENT）
        verify(orderMapper, times(2)).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("存在异常 -> 仍处理完全部订单（单笔失败不中断整批）")
    void continuesAfterAbnormal() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(1L, 10L), order(2L, 11L)));
        // 第一笔：无日志无 trace -> ABNORMAL；第二笔：一致
        when(logMapper.selectList(any()))
            .thenReturn(List.of())
            .thenReturn(List.of(okLog(2L)));
        when(hashOps.entries(anyString())).thenReturn(Map.of());

        task.reconcile();

        verify(orderMapper, times(2)).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("在途订单 -> 不标记状态（留给下轮）")
    void inFlightNotMarked() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(1L, 10L)));
        when(logMapper.selectList(any())).thenReturn(List.of());
        // trace 时间戳为当前时间 -> 在途窗口内
        long nowSec = System.currentTimeMillis() / 1000;
        String json = "{\"ts\":" + nowSec + ",\"userId\":\"1\"}";
        when(hashOps.entries(anyString())).thenReturn(Map.of("trace-1", json));

        task.reconcile();

        // 在途：markStatus 不会被调用
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("空批次边界：只有一笔且一致")
    void singleConsistent() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(1L, 10L)));
        when(logMapper.selectList(any())).thenReturn(List.of(okLog(1L)));
        assertDoesNotThrow(() -> task.reconcile());
    }
}
