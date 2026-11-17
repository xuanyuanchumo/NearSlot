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
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Hold 超时回收单测（FR-3.3）。
 *
 * <p>**2026-11-17 审计修复后的语义**（Major）：
 * 必须「**先回补成功、再置 CANCELLED**」。
 * 原实现先改状态再回补，回补失败抛异常时状态已落库为 CANCELLED，
 * 而兜底扫描只捞 PENDING —— 这个订单从此无人处理，余量永久泄漏。
 * 现改为回补失败保持 PENDING，下一轮扫描重试。
 *
 * <p>覆盖：延迟消息投递 / 消费者幂等闸 / 正常回补并改状态 / 回补失败保持 PENDING /
 * 时段缺失 / payload 非法 / 兜底扫描。
 */
class HoldTimeoutTaskTest {

    private AppointmentOrderMapper orderMapper;
    private TimeSlotMapper timeSlotMapper;
    private AppointmentLuaService luaService;
    private RedissonDelayQueue delayQueue;
    private ApplicationEventPublisher eventPublisher;
    private HoldTimeoutTask task;

    @BeforeEach
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        timeSlotMapper = mock(TimeSlotMapper.class);
        luaService = mock(AppointmentLuaService.class);
        delayQueue = mock(RedissonDelayQueue.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        task = new HoldTimeoutTask(orderMapper, timeSlotMapper, luaService, delayQueue,
            eventPublisher);
    }

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
        s.setCapacity(1);
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

    @SuppressWarnings("unchecked")
    private Consumer<Object> startAndCaptureConsumer() {
        ArgumentCaptor<Consumer> cap = ArgumentCaptor.forClass(Consumer.class);
        task.startConsumer();
        verify(delayQueue).startConsumer(eq(HoldTimeoutTask.HOLD_QUEUE), cap.capture());
        return (Consumer<Object>) cap.getValue();
    }

    @Test
    @DisplayName("scheduleExpire: 按订单 ID 投递延迟消息到 Hold 队列")
    void scheduleExpireOffersPayload() {
        task.scheduleExpire(order(1001L, OrderStatus.PENDING.getCode(), 2001L), 600L);
        verify(delayQueue).offer(eq(HoldTimeoutTask.HOLD_QUEUE), eq("1001"), eq(600L));
    }

    @Test
    @DisplayName("消费者: 订单不存在 -> 忽略（幂等）")
    void consumerIgnoresMissingOrder() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(null);

        consumer.accept("1001");

        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("消费者: 订单已支付 -> 忽略，不重复回补")
    void consumerIgnoresNonPendingOrder() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.CONFIRMED.getCode(), 2001L));

        consumer.accept("1001");

        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("消费者: 待支付超时 -> 回补成功后才置 CANCELLED，并触发候补提升")
    void consumerExpiresPendingOrder() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.PENDING.getCode(), 2001L));
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt(), anyInt())).thenReturn(luaResult(200));

        consumer.accept("1001");

        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.CANCELLED.getCode()));
        // capacity 必须传给回补脚本（INCR 后封顶，防重复回补导致超卖）
        verify(luaService).rollback(eq(3001L), eq(2001L), eq(4001L), anyLong(), anyLong(),
            eq(1001L), anyLong(), eq(1), eq(1));
        // 超时回收同样释放了余量，必须提升候补（与取消路径行为一致）
        // 超时回收同样释放了余量，必须发布 slot.opened 事件触发候补提升（与取消路径一致）
        verify(eventPublisher).publishEvent(new org.nearslot.appointment.event.SlotOpenedEvent(2001L));
    }

    @Test
    @DisplayName("消费者: 回补失败 -> 订单保持 PENDING（可被下一轮重试），不置 CANCELLED")
    void consumerKeepsPendingWhenRollbackFails() {
        Consumer<Object> consumer = startAndCaptureConsumer();
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.PENDING.getCode(), 2001L));
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt(), anyInt())).thenReturn(luaResult(10004));

        assertDoesNotThrow(() -> consumer.accept("1001"));

        // 关键：状态没被改，否则它再也不会被兜底扫描捞到
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("消费者: payload 非数字 -> 异常被吞，不触碰 DB")
    void consumerSwallowsBadPayload() {
        Consumer<Object> consumer = startAndCaptureConsumer();

        assertDoesNotThrow(() -> consumer.accept("not-a-number"));

        verify(orderMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("expireOne: 时段不存在 -> 告警返回，不改状态不回补")
    void expireOneSkipsWhenSlotMissing() {
        when(timeSlotMapper.selectById(2001L)).thenReturn(null);

        task.expireOne(order(1001L, OrderStatus.PENDING.getCode(), 2001L));

        verify(orderMapper, never()).selectById(anyLong());
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("expireOne: 二次读取发现已非待支付 -> 幂等跳过")
    void expireOneIdempotentWhenAlreadyHandled() {
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.CANCELLED.getCode(), 2001L));

        task.expireOne(order(1001L, OrderStatus.PENDING.getCode(), 2001L));

        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("expireOne: 二次读取订单已消失 -> 幂等跳过")
    void expireOneIdempotentWhenOrderGone() {
        when(timeSlotMapper.selectById(2001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(null);

        task.expireOne(order(1001L, OrderStatus.PENDING.getCode(), 2001L));

        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("兜底扫描: 无过期订单 -> 不做任何回补")
    void scanWithNoExpiredOrders() {
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of());

        task.scanExpiredHolds();

        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("兜底扫描: 单笔失败不影响其余订单")
    void scanContinuesAfterFailure() {
        when(orderMapper.selectList(any())).thenReturn(java.util.List.of(
            order(1001L, OrderStatus.PENDING.getCode(), 2001L),
            order(1002L, OrderStatus.PENDING.getCode(), 2002L)));
        when(timeSlotMapper.selectById(2001L)).thenReturn(null);
        when(timeSlotMapper.selectById(2002L)).thenReturn(null);

        assertDoesNotThrow(() -> task.scanExpiredHolds());

        verify(timeSlotMapper).selectById(2001L);
        verify(timeSlotMapper).selectById(2002L);
    }
}
