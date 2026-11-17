package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 支付回调幂等与竞态测试（FR-4.2）。
 * 关键场景：重复回调、已取消后退款、状态异常。
 */
class PaymentServiceTest {

    private AppointmentOrderMapper orderMapper;
    private TimeSlotMapper slotMapper;
    private ReconcileService reconcileService;
    private DistributedLockTemplate lockTemplate;
    private PaymentService paymentService;
    private TechRankService techRankService;
    private PrepareReminderService prepareReminderService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        slotMapper = mock(TimeSlotMapper.class);
        reconcileService = mock(ReconcileService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        // 用直通锁替代真实 Redisson，聚焦业务逻辑
        lockTemplate = mock(DistributedLockTemplate.class);
        when(lockTemplate.withLock(any(), anyLong(), anyLong(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());

        techRankService = mock(TechRankService.class);
        prepareReminderService = mock(PrepareReminderService.class);
        paymentService = new PaymentService(orderMapper, slotMapper, reconcileService,
            lockTemplate, redis, techRankService, prepareReminderService);
    }

    private AppointmentOrder order(int status) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(1001L);
        o.setUserId(2001L);
        o.setSlotId(3001L);
        o.setServiceStaffId(4001L);
        o.setStatus(status);
        return o;
    }

    private TimeSlot slot() {
        TimeSlot s = new TimeSlot();
        s.setId(3001L);
        s.setStartsAt(LocalDateTime.now().plusDays(1));
        s.setEndsAt(LocalDateTime.now().plusDays(1).plusHours(1));
        return s;
    }

    @Test
    @DisplayName("PENDING 订单首次支付 -> CONFIRMED")
    void firstPayment_confirms() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.PENDING.getCode()));
        when(slotMapper.selectById(3001L)).thenReturn(slot());

        assertEquals(PaymentService.PayResult.CONFIRMED, paymentService.onPaid(1001L, "PAY001"));
        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.CONFIRMED.getCode()));
    }

    @Test
    @DisplayName("重复回调 -> IDEMPOTENT，不重复处理")
    void duplicateCallback_isIdempotent() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.CONFIRMED.getCode()));

        assertEquals(PaymentService.PayResult.IDEMPOTENT, paymentService.onPaid(1001L, "PAY001"));
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("已取消订单收到支付 -> NEED_REFUND（防超卖）")
    void paidAfterCancelled_needsRefund() {
        when(orderMapper.selectById(1001L)).thenReturn(order(OrderStatus.CANCELLED.getCode()));

        assertEquals(PaymentService.PayResult.NEED_REFUND, paymentService.onPaid(1001L, "PAY002"));
        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.REFUNDED.getCode()));
    }

    @Test
    @DisplayName("订单不存在 -> 抛 NOT_FOUND")
    void orderNotFound_throws() {
        when(orderMapper.selectById(9999L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> paymentService.onPaid(9999L, "PAY003"));
    }
}
