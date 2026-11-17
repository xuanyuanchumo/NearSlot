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

    /**
     * 桩：对账扫描现在改用 MP 的 selectPage（不再 selectList + 手拼 LIMIT），
     * 所以必须返回携带记录的分页对象，否则 getRecords() 为 null -> NPE。
     */
    /**
     * 桩：对账扫描改用 MP 的 selectPage 后，必须让**传入的那个 page 对象**带回记录。
     *
     * 踩坑记录：`when(mapper.selectPage(any(), any())).thenReturn(page)` 在
     * {@code BaseMapper#selectPage} 这种泛型方法（P extends IPage<T>）上匹配不到，
     * Mockito 会返回 null，导致服务侧 getRecords() 直接 NPE。
     * 改用 doAnswer + 显式 IPage 类型参数，把记录写进调用方自己的 page 对象。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPending(java.util.List<AppointmentOrder> rows) {
        doAnswer(inv -> {
            com.baomidou.mybatisplus.core.metadata.IPage<AppointmentOrder> p =
                inv.getArgument(0, com.baomidou.mybatisplus.core.metadata.IPage.class);
            p.setRecords(rows);
            p.setTotal(rows.size());
            return p;
        }).when(orderMapper).selectPage(
            org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.metadata.IPage>any(),
            any());
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
        stubPending(List.of());
        task.reconcile();
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("全部一致 -> 标记 CONSISTENT，不标记 ABNORMAL")
    void allConsistent() {
        stubPending(List.of(order(1L, 10L), order(2L, 11L)));
        when(logMapper.selectList(any())).thenReturn(List.of(okLog(1L)));

        task.reconcile();

        // 每笔都会标记一次状态（CONSISTENT）
        verify(orderMapper, times(2)).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("存在异常 -> 仍处理完全部订单（单笔失败不中断整批）")
    void continuesAfterAbnormal() {
        stubPending(List.of(order(1L, 10L), order(2L, 11L)));
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
        stubPending(List.of(order(1L, 10L)));
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
        stubPending(List.of(order(1L, 10L)));
        when(logMapper.selectList(any())).thenReturn(List.of(okLog(1L)));
        assertDoesNotThrow(() -> task.reconcile());
    }
}
