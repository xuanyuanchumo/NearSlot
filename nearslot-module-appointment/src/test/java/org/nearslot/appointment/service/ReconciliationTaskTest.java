package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.ReconcileLog;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.ReconcileLogMapper;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 对账任务判定逻辑单测（无需真实 DB/Redis）。
 * 覆盖：一致 / 无日志且无 trace（异常）/ 在途跳过 / 日志含 FAIL（异常）/ 日志过多（异常）。
 */
class ReconciliationTaskTest {

    private AppointmentOrderMapper orderMapper;
    private ReconcileLogMapper logMapper;
    private StringRedisTemplate redis;
    private ReconciliationTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        logMapper = mock(ReconcileLogMapper.class);
        redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(any())).thenReturn(Map.of());
        task = new ReconciliationTask(orderMapper, logMapper, redis,
            new org.nearslot.appointment.config.AppointmentProperties());
    }

    private AppointmentOrder order() {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(1001L);
        o.setSlotId(2001L);
        o.setUserId(3001L);
        return o;
    }

    private ReconcileLog log(int result) {
        ReconcileLog l = new ReconcileLog();
        l.setOrderId(1001L);
        l.setResult(result);
        l.setLogType(1);
        return l;
    }

    @Test
    @DisplayName("有成功日志 -> CONSISTENT")
    void withSuccessLog_isConsistent() {
        when(logMapper.selectList(any())).thenReturn(List.of(log(1)));
        assertEquals(ReconciliationTask.Verdict.CONSISTENT, task.checkOne(order()));
        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getReconcileStatus() == 4));
    }

    @Test
    @DisplayName("无日志且无 trace -> ABNORMAL（订单本不该存在）")
    void noLogNoTrace_isAbnormal() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        assertEquals(ReconciliationTask.Verdict.ABNORMAL, task.checkOne(order()));
    }

    @Test
    @DisplayName("无日志但有 trace 且在途 -> IN_FLIGHT（不误判）")
    @SuppressWarnings("unchecked")
    void traceInFlight_isSkipped() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        HashOperations<String, Object, Object> hashOps = redis.opsForHash();
        long nowSec = System.currentTimeMillis() / 1000;
        when(hashOps.entries(any())).thenReturn(
            Map.of("t1", "{\"logType\":1,\"ts\":" + nowSec + "}"));
        assertEquals(ReconciliationTask.Verdict.IN_FLIGHT, task.checkOne(order()));
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("无日志但有 trace 且已超窗 -> ABNORMAL")
    @SuppressWarnings("unchecked")
    void traceExpired_isAbnormal() {
        when(logMapper.selectList(any())).thenReturn(List.of());
        HashOperations<String, Object, Object> hashOps = redis.opsForHash();
        long oldSec = (System.currentTimeMillis() - 60_000) / 1000;
        when(hashOps.entries(any())).thenReturn(
            Map.of("t1", "{\"logType\":1,\"ts\":" + oldSec + "}"));
        assertEquals(ReconciliationTask.Verdict.ABNORMAL, task.checkOne(order()));
    }

    @Test
    @DisplayName("日志含 FAIL -> ABNORMAL（走过回补路径需人工确认）")
    void failureLog_isAbnormal() {
        when(logMapper.selectList(any())).thenReturn(List.of(log(1), log(2)));
        assertEquals(ReconciliationTask.Verdict.ABNORMAL, task.checkOne(order()));
    }

    @Test
    @DisplayName("日志条数 >2 -> ABNORMAL（重复处理）")
    void tooManyLogs_isAbnormal() {
        when(logMapper.selectList(any())).thenReturn(List.of(log(1), log(1), log(1)));
        assertEquals(ReconciliationTask.Verdict.ABNORMAL, task.checkOne(order()));
    }
}
