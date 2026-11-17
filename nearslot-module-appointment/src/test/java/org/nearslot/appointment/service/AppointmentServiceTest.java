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
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.LogType;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 抢约编排单测（FR-3.1 / FR-3.3 / FR-4.1）。
 *
 * <p>2026-11-17 重构后：预扣+落单下沉到 {@link SlotBookingCore}（事务边界真实生效、
 * 候补提升可复用），本类只负责「等级闸 + 幂等 + Hold 排期」与「取消状态 CAS + 回补」。
 *
 * <p>覆盖分支：等级闸（不足·满足·无门槛null·无门槛0）/ 取消状态机 / 取消时时段缺失不 NPE /
 * 回补成功才提升候补 / 回补失败不提升 / 订单详情越权 / 列表过滤。
 *
 * <p>依赖全部 Mock，不连真实 Redis/MySQL。
 */
class AppointmentServiceTest {

    private static final long SLOT_ID = 2001L;
    private static final long USER_ID = 3001L;
    private static final long STAFF_ID = 4001L;
    private static final long ORDER_ID = 1001L;

    private SlotBookingCore bookingCore;
    private TimeSlotMapper timeSlotMapper;
    private AppointmentOrderMapper orderMapper;
    private HoldTimeoutTask holdTimeoutTask;
    private ReconcileService reconcileService;
    private UserLevelQuery userLevelQuery;
    private ApplicationEventPublisher eventPublisher;
    private AppointmentLuaService luaService;
    private VoucherService voucherService;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        bookingCore = mock(SlotBookingCore.class);
        timeSlotMapper = mock(TimeSlotMapper.class);
        orderMapper = mock(AppointmentOrderMapper.class);
        holdTimeoutTask = mock(HoldTimeoutTask.class);
        reconcileService = mock(ReconcileService.class);
        userLevelQuery = mock(UserLevelQuery.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        luaService = mock(AppointmentLuaService.class);
        voucherService = mock(VoucherService.class);
        // 构造器顺序严格对齐 @RequiredArgsConstructor 的字段声明顺序
        service = new AppointmentService(bookingCore, timeSlotMapper, orderMapper, holdTimeoutTask,
            reconcileService, userLevelQuery, eventPublisher, luaService, voucherService);
    }

    private TimeSlot slot(Integer status, Integer minLevel) {
        TimeSlot s = new TimeSlot();
        s.setId(SLOT_ID);
        s.setShopId(5001L);
        s.setServiceStaffId(STAFF_ID);
        s.setCapacity(5);
        s.setStatus(status);
        s.setMinLevel(minLevel);
        s.setStartsAt(LocalDateTime.now().plusDays(1));
        s.setEndsAt(LocalDateTime.now().plusDays(1).plusHours(1));
        return s;
    }

    private BookResult luaResult(int code) {
        BookResult r = new BookResult();
        r.setCode(code);
        r.setBeforeQty(10);
        r.setDeductQty(1);
        r.setAfterQty(9);
        return r;
    }

    private AppointmentOrder order(int status, long userId) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(ORDER_ID);
        o.setUserId(userId);
        o.setSlotId(SLOT_ID);
        o.setShopId(5001L);
        o.setServiceStaffId(STAFF_ID);
        o.setStatus(status);
        return o;
    }

    // ------------------------------------------------------------------ book()

    @Test
    @DisplayName("book: 时段不存在 -> NOT_FOUND，不进入内核")
    void bookSlotMissing() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(bookingCore);
    }

    @Test
    @DisplayName("book: 等级不足 -> LEVEL_NOT_MET(50104)，不进入内核")
    void bookLevelNotMet() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 3));
        when(userLevelQuery.levelOf(USER_ID)).thenReturn(2);

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.LEVEL_NOT_MET, ex.getCode());
        verifyNoInteractions(bookingCore);
    }

    @Test
    @DisplayName("book: 等级恰好达标 -> 放行")
    void bookLevelExactlyMet() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 3));
        when(userLevelQuery.levelOf(USER_ID)).thenReturn(3);
        when(bookingCore.book(USER_ID, SLOT_ID)).thenReturn(ORDER_ID);

        assertEquals(ORDER_ID, service.book(USER_ID, SLOT_ID));
    }

    @Test
    @DisplayName("book: minLevel=null（无门槛）-> 不查等级")
    void bookMinLevelNullSkipsLevelQuery() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, null));
        when(bookingCore.book(USER_ID, SLOT_ID)).thenReturn(ORDER_ID);

        service.book(USER_ID, SLOT_ID);

        verifyNoInteractions(userLevelQuery);
    }

    @Test
    @DisplayName("book: minLevel=0（无门槛）-> 不查等级")
    void bookMinLevelZeroSkipsLevelQuery() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(bookingCore.book(USER_ID, SLOT_ID)).thenReturn(ORDER_ID);

        service.book(USER_ID, SLOT_ID);

        verifyNoInteractions(userLevelQuery);
    }

    @Test
    @DisplayName("book: 成功 -> 投递 Hold 超时延迟消息（用内核暴露的 Hold 秒数）")
    void bookSchedulesHoldExpiry() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(bookingCore.book(USER_ID, SLOT_ID)).thenReturn(ORDER_ID);
        when(bookingCore.holdSeconds()).thenReturn(600L);

        service.book(USER_ID, SLOT_ID);

        ArgumentCaptor<AppointmentOrder> captor = ArgumentCaptor.forClass(AppointmentOrder.class);
        verify(holdTimeoutTask).scheduleExpire(captor.capture(), eq(600L));
        assertEquals(ORDER_ID, captor.getValue().getId());
    }

    // ------------------------------------------------------------------ queryOrder()

    @Test
    @DisplayName("queryOrder: 本人订单 -> 返回")
    void queryOrderOwn() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), USER_ID));
        assertNotNull(service.queryOrder(USER_ID, ORDER_ID));
    }

    @Test
    @DisplayName("queryOrder: 订单不存在 -> NOT_FOUND")
    void queryOrderMissing() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class, () -> service.queryOrder(USER_ID, ORDER_ID));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("queryOrder: 他人订单 -> 统一按 NOT_FOUND（不泄露存在性）")
    void queryOrderForeignMaskedAsNotFound() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), 9999L));
        BaseException ex = assertThrows(BaseException.class, () -> service.queryOrder(USER_ID, ORDER_ID));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    // ------------------------------------------------------------------ listMyOrders()

    @Test
    @DisplayName("listMyOrders: 不带 status -> 查全部")
    void listMyOrdersWithoutStatus() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        assertTrue(service.listMyOrders(USER_ID, null).isEmpty());
        verify(orderMapper).selectList(any());
    }

    @Test
    @DisplayName("listMyOrders: 带 status -> 透传给查询条件")
    void listMyOrdersWithStatus() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        service.listMyOrders(USER_ID, OrderStatus.CONFIRMED.getCode());
        verify(orderMapper).selectList(any());
    }

    // ------------------------------------------------------------------ cancel()

    @Test
    @DisplayName("cancel: 订单不存在 -> NOT_FOUND")
    void cancelMissingOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("cancel: 非本人订单 -> NOT_FOUND（不泄露）")
    void cancelForeignOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), 9999L));
        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("cancel: 已完成订单 -> 拒绝（状态机）")
    void cancelDoneOrderRejected() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.DONE.getCode(), USER_ID));
        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));
        assertTrue(ex.getMessage().contains("不可取消"));
    }

    @Test
    @DisplayName("cancel: 已取消订单 -> 拒绝（状态机）")
    void cancelCancelledOrderRejected() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.CANCELLED.getCode(), USER_ID));
        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));
        assertTrue(ex.getMessage().contains("不可取消"));
    }

    @Test
    @DisplayName("cancel: CAS 失败（并发已改状态）-> 拒绝，不回补")
    void cancelCasConflictRejected() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), USER_ID));
        // CAS 条件更新影响 0 行 = 状态已被并发修改
        when(orderMapper.update(any(), any())).thenReturn(0);

        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));

        assertTrue(ex.getMessage().contains("已被变更"));
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("cancel: 时段已删除 -> 只改状态 + 记对账，不 NPE（订单不卡死）")
    void cancelSlotMissingDoesNotCrash() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), USER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(null);

        assertDoesNotThrow(() -> service.cancel(USER_ID, ORDER_ID));

        verify(luaService, never()).rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyLong(), anyInt(), anyInt());
        verify(reconcileService).writeBestEffort(eq(ORDER_ID), eq(LogType.RESTORE), eq(false),
            anyInt(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("cancel: 回补成功 -> 触发候补提升")
    void cancelConfirmedOrderTriggersPromotion() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.CONFIRMED.getCode(), USER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt(), anyInt())).thenReturn(luaResult(200));

        service.cancel(USER_ID, ORDER_ID);

        verify(eventPublisher).publishEvent(new org.nearslot.appointment.event.SlotOpenedEvent(SLOT_ID));
    }

    @Test
    @DisplayName("cancel: 回补失败 -> 仍写对账日志但不提升候补")
    void cancelRollbackFailedStillLogs() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), USER_ID));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt(), anyInt())).thenReturn(luaResult(10004));

        service.cancel(USER_ID, ORDER_ID);

        verify(eventPublisher, never()).publishEvent(any());
        verify(reconcileService).writeBestEffort(eq(ORDER_ID), eq(LogType.RESTORE), eq(false),
            anyInt(), anyInt(), anyInt(), anyLong(), anyString());
    }
}
