package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.event.SlotOpenedEvent;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.OrderStatus;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HoldExpiryExecutor 单测（评审 B-001 拆分后的业务逻辑归属）。
 *
 * 这些断言原先在 HoldTimeoutTaskTest.expireOne* 中；拆 Bean 后移植到这里，
 * 确保"拆结构"没有丢掉测试覆盖（否则覆盖率下降且逻辑裸奔）。
 */
class HoldExpiryExecutorTest {

    private AppointmentOrderMapper orderMapper;
    private TimeSlotMapper timeSlotMapper;
    private AppointmentLuaService luaService;
    private ApplicationEventPublisher eventPublisher;
    private HoldExpiryExecutor executor;

    @BeforeEach
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        timeSlotMapper = mock(TimeSlotMapper.class);
        luaService = mock(AppointmentLuaService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        executor = new HoldExpiryExecutor(orderMapper, timeSlotMapper, luaService, eventPublisher);
    }

    private AppointmentOrder order(long id, int status) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(id);
        o.setUserId(1001L);
        o.setSlotId(3001L);
        o.setServiceStaffId(2001L);
        o.setStatus(status);
        return o;
    }

    private TimeSlot slot() {
        TimeSlot s = new TimeSlot();
        s.setId(3001L);
        s.setStartsAt(LocalDateTime.now().plusHours(1));
        s.setEndsAt(LocalDateTime.now().plusHours(2));
        s.setCapacity(1);
        return s;
    }

    private BookResult ok() {
        BookResult r = new BookResult();
        r.setCode(200);
        r.setBeforeQty(1);
        r.setDeductQty(1);
        r.setAfterQty(0);
        return r;
    }

    @Test
    @DisplayName("时段不存在 -> 告警返回，不改状态不回补")
    void skipsWhenSlotMissing() {
        when(timeSlotMapper.selectById(3001L)).thenReturn(null);

        executor.expireOne(order(1001L, OrderStatus.PENDING.getCode()));

        verify(orderMapper, never()).selectById(anyLong());
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("二次读取已非待支付 -> 幂等跳过")
    void idempotentWhenAlreadyHandled() {
        when(timeSlotMapper.selectById(3001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.CANCELLED.getCode()));

        executor.expireOne(order(1001L, OrderStatus.PENDING.getCode()));

        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("回补成功 -> 置 CANCELLED 并发布 SlotOpenedEvent")
    void expiresAndPublishesEvent() {
        when(timeSlotMapper.selectById(3001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.PENDING.getCode()));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(ok());

        executor.expireOne(order(1001L, OrderStatus.PENDING.getCode()));

        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            o -> o.getStatus() == OrderStatus.CANCELLED.getCode()));
        verify(eventPublisher).publishEvent(any(SlotOpenedEvent.class));
    }

    @Test
    @DisplayName("回补失败 -> 抛异常保持 PENDING，不发布事件")
    void keepsPendingWhenRollbackFails() {
        when(timeSlotMapper.selectById(3001L)).thenReturn(slot());
        when(orderMapper.selectById(1001L)).thenReturn(order(1001L, OrderStatus.PENDING.getCode()));
        BookResult fail = ok();
        fail.setCode(10006);
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyLong(), anyInt(), anyInt())).thenReturn(fail);

        assertThrows(IllegalStateException.class,
            () -> executor.expireOne(order(1001L, OrderStatus.PENDING.getCode())));
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verifyNoInteractions(eventPublisher);
    }
}
