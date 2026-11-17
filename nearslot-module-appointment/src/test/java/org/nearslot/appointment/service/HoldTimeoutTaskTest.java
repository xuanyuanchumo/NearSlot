package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Hold 超时回收单测（FR-3.3）。
 *
 * <p>覆盖：延迟消息投递 / 消费者幂等闸（订单不存在·已支付）/ 正常回补 /
 * 时段缺失 / 回补失败抛错 / payload 非法 / 兜底扫描空列表 / 兜底扫描跳过已处理 /
 * 兜底扫描单笔失败不影响其余订单。
 */
class HoldTimeoutTaskTest {

    private AppointmentOrderMapper orderMapper;
    private TimeSlotMapper timeSlotMapper;
    private AppointmentLuaService luaService;
    private RedissonDelayQueue delayQueue;
    private HoldTimeoutTask task;

    @BeforeEach
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        timeSlotMapper = mock(TimeSlotMapper.class);
        luaService = mock(AppointmentLuaService.class);
        delayQueue = mock(RedissonDelayQueue.class);
        task = new HoldTimeoutTask(orderMapper, timeSlotMapper, luaService, delayQueue);
    }

    // ------------------------------------------------------------------ fixtures

    private AppointmentOrder order(long id, int status, long slotId) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(id);
        o.setUserId(3001L);
        o.setSlotId(slotId);
        o.setServiceStaffId(4001L);
        o.setStatus(status);
        o.setHoldExpiresAt(LocalDateTime.now().minusMinutes(1));
        return o;
    }

    private TimeSlot slot() {
        TimeSlot s = new TimeSlot();
        s.setId(2001L);
        s.setStartsAt(LocalDateTime.now().plusDays(1));
        s.setEndsAt(LocalDateTime.now().plusDays(1).plusHours(1));
        return s;
    }

    private BookResult luaResult(int code) {
        BookResult r = new BookResult();
        r.setCode(code);
        r.setBeforeQty(9);
        r.setDeductQty(1);
        r.setAfterQty(10);
        return r;
    }

    /** 启动消费者并捕获回调，用于直接驱动主路径。 */
    @SuppressWarnings("unchecked")
    private Consumer<Object> startAndCaptureConsumer() {
        ArgumentCaptor<Consumer> cap = ArgumentCaptor.forClass(Consumer.class);
        task.startConsumer();
        verify(delayQueue).startConsumer(eq(HoldTimeoutTask.HOLD_QUEUE), cap.capture());
        return (Consumer<Object>) cap.getValue();
    }

    // ------------------------------------------------------------------ scheduleExpire

    @Test
    @DisplayName("scheduleExpire: 按订单 ID 投递延迟消息到 Hold 队列")
    void scheduleExpireOffersPayload() {
        task.scheduleExpire(order(1001L, OrderStatus.PENDING.getCode(), 2001L), 600L);

        verify(delayQueue).offer(eq(HoldTimeoutTask.HOLD_QUEUE), eq("1001"), eq(600L));
    }

    // ------------------------------------------------------------------ startConsumer

    @Test
    @DisplayName("消费者: 订单不存在 -> 忽略（幂等）")
    void consumerIgnoresMissingOrder() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(null);

        consumer.accept("1001");

        verify(orderMapper).selectById(1001L);
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("消费者: 订单已支付/已取消 -> 忽略，不重复回补")
    void consumerIgnoresNonPendingOrder() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.CONFIRMED.getCode(), 2001L));

        consumer.accept("1001");

        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("消费者: 待支付订单超时 -> 置 CANCELLED 并回补余量")
    void consumerExpiresPendingOrder() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        AppointmentOrder pending = order(1001L, OrderStatus.PENDING.getCode(), 2001L);
        when(orderMapper.selectById(1001L)).thenReturn(pending);
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(200));

        consumer.accept("1001");

        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.CANCELLED.getCode()));
        verify(luaService).rollback(eq(3001L), eq(2001L), eq(4001L), anyLong(), anyLong(),
            eq(1001L), anyLong(), eq(1));
    }

    @Test
    @DisplayName("消费者: 回补失败 -> 异常被吞（不中断消费线程），但状态已置 CANCELLED")
    void consumerSwallowsRollbackFailure() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.PENDING.getCode(), 2001L));
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(50001));

        assertDoesNotThrow(() -> consumer.accept("1001"));

        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.CANCELLED.getCode()));
    }

    @Test
    @DisplayName("消费者: payload 非数字 -> 异常被吞，不触碰 DB")
    void consumerSwallowsBadPayload() {
        Consumer<Object> consumer = startAndCaptureConsumer();

        assertDoesNotThrow(() -> consumer.accept("not-a-number"));

        verify(orderMapper, never()).selectById(anyLong());
    }

    // ------------------------------------------------------------------ expireOne

    @Test
    @DisplayName("expireOne: 时段不存在 -> 告警返回，不改状态不回补")
    void expireOneSkipsWhenSlotMissing() {
        AppointmentOrder o = order(1001L, OrderStatus.PENDING.getCode(), 2001L);
        when(timeSlotMapper.selectById(2001L)).thenReturn(null);

        task.expireOne(o);

        verify(orderMapper, never()).selectById(anyLong());
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("expireOne: 二次读取发现已非待支付 -> 幂等跳过（延迟队列与扫描重复投递）")
    void expireOneIdempotentWhenAlreadyHandled() {
        AppointmentOrder o = order(1001L, OrderStatus.PENDING.getCode(), 2001L);
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.CANCELLED.getCode(), 2001L));

        task.expireOne(o);

        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("expireOne: 二次读取订单已消失 -> 幂等跳过")
    void expireOneIdempotentWhenOrderGone() {
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(null);

        task.expireOne(order(1001L, OrderStatus.PENDING.getCode(), 2001L));

        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("expireOne: 回补失败 -> 抛 IllegalStateException（交由事务回滚）")
    void expireOneThrowsWhenRollbackFails() {
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.PENDING.getCode(), 2001L));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(50001));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> task.expireOne(order(1001L, OrderStatus.PENDING.getCode(), 2001L)));

        assertTrue(ex.getMessage().contains("50001"), "异常文案应带失败码，实际: " + ex.getMessage());
    }

    // ------------------------------------------------------------------ scanExpiredHolds

    @Test
    @DisplayName("scanExpiredHolds: 无过期订单 -> 直接返回，不回补")
    void scanSkipsWhenEmpty() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        task.scanExpiredHolds();

        verify(orderMapper).selectList(any());
        verify(orderMapper, never()).selectById(anyLong());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("scanExpiredHolds: 扫到过期订单 -> 逐笔回补，再读已非待支付则跳过")
    void scanSkipsAlreadyHandledOrder() {
        when(orderMapper.selectList(any())).thenReturn(
            List.of(order(1001L, OrderStatus.PENDING.getCode(), 2001L)));
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.CONFIRMED.getCode(), 2001L));

        task.scanExpiredHolds();

        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("scanExpiredHolds: 单笔失败不中断整批（其余订单仍被回补）")
    void scanContinuesAfterSingleFailure() {
        when(orderMapper.selectList(any())).thenReturn(List.of(
            order(1L, OrderStatus.PENDING.getCode(), 2001L),
            order(2L, OrderStatus.PENDING.getCode(), 2002L)));
        when(timeSlotMapper.selectById(anyLong())).thenReturn(slot());
        when(orderMapper.selectById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            long slotId = id == 1L ? 2001L : 2002L;
            return order(id, OrderStatus.PENDING.getCode(), slotId);
        });
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenAnswer(inv -> {
            Long orderId = inv.getArgument(5);
            return orderId == 1L ? luaResult(50001) : luaResult(200);
        });

        assertDoesNotThrow(() -> task.scanExpiredHolds());

        // 两笔都被置 CANCELLED；第一笔回补失败被 catch，第二笔照常处理
        verify(orderMapper, times(2)).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.CANCELLED.getCode()));
        verify(luaService, times(2)).rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyLong(), anyInt());
    }
}
