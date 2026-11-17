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
import org.nearslot.framework.lock.DistributedLockTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 抢约编排单测（FR-3.1 / FR-3.3 / FR-4.1）。
 *
 * <p>覆盖分支：时段不存在 / 时段关闭 / 等级闸（不足·满足·无门槛）/ Lua 成功 / Lua 失败码 /
 * 落库失败回补 / 延迟消息投递失败回补 / 对账日志写入 / 订单详情越权 / 取消状态机 /
 * 回补成功触发候补提升 / 回补失败不提升。
 *
 * <p>依赖全部 Mock，不连真实 Redis/MySQL。
 */
class AppointmentServiceTest {

    private static final long SLOT_ID = 2001L;
    private static final long USER_ID = 3001L;
    private static final long STAFF_ID = 4001L;
    private static final long ORDER_ID = 1001L;

    private AppointmentLuaService luaService;
    private TimeSlotMapper timeSlotMapper;
    private AppointmentOrderMapper orderMapper;
    private DistributedLockTemplate lockTemplate;
    private HoldTimeoutTask holdTimeoutTask;
    private ReconcileService reconcileService;
    private UserLevelQuery userLevelQuery;
    private PromotionService promotionService;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        luaService = mock(AppointmentLuaService.class);
        timeSlotMapper = mock(TimeSlotMapper.class);
        orderMapper = mock(AppointmentOrderMapper.class);
        lockTemplate = mock(DistributedLockTemplate.class);
        holdTimeoutTask = mock(HoldTimeoutTask.class);
        reconcileService = mock(ReconcileService.class);
        userLevelQuery = mock(UserLevelQuery.class);
        promotionService = mock(PromotionService.class);
        // 构造器顺序严格对齐 @RequiredArgsConstructor 的字段声明顺序
        service = new AppointmentService(luaService, timeSlotMapper, orderMapper, lockTemplate,
            holdTimeoutTask, reconcileService, userLevelQuery, promotionService);
    }

    // ------------------------------------------------------------------ fixtures

    private TimeSlot slot(Integer status, Integer minLevel) {
        TimeSlot s = new TimeSlot();
        s.setId(SLOT_ID);
        s.setShopId(5001L);
        s.setServiceStaffId(STAFF_ID);
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

    /** 让 book() 的三检查走到成功分支。 */
    private void stubSuccessfulBook() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
            .thenReturn(luaResult(200));
    }

    // ------------------------------------------------------------------ book()

    @Test
    @DisplayName("book: 时段不存在 -> 抛 NOT_FOUND，且不进入 Lua")
    void bookSlotMissing() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(luaService);
        verify(orderMapper, never()).insert(any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("book: 时段状态为 null -> 抛 SLOT_CLOSED")
    void bookSlotStatusNull() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(null, 0));

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.SLOT_CLOSED, ex.getCode());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("book: 时段状态非 1（已关闭/已满） -> 抛 SLOT_CLOSED")
    void bookSlotClosed() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(0, 0));

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.SLOT_CLOSED, ex.getCode());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("book: 会员等级不足 -> 抛 LEVEL_NOT_MET，在 Lua 之前短路")
    void bookLevelNotMet() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 3));
        when(userLevelQuery.levelOf(USER_ID)).thenReturn(1);

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.LEVEL_NOT_MET, ex.getCode());
        assertTrue(ex.getMessage().contains("Lv3"), "异常文案应提示所需等级，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("当前 Lv1"), "异常文案应提示当前等级，实际: " + ex.getMessage());
        // 等级不足必须在 Lua 之前短路，避免无效 Redis 写入
        verifyNoInteractions(luaService);
        verify(orderMapper, never()).insert(any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("book: 等级达标 -> 放行（幂等比较用 < 而非 <=）")
    void bookLevelExactlyMet() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 2));
        when(userLevelQuery.levelOf(USER_ID)).thenReturn(2);
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
            .thenReturn(luaResult(200));

        Long orderId = service.book(USER_ID, SLOT_ID);

        assertNotNull(orderId);
        verify(userLevelQuery).levelOf(USER_ID);
        verify(luaService).book(eq(USER_ID), eq(SLOT_ID), eq(STAFF_ID), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("book: minLevel 为 null -> 视为无门槛，不查等级")
    void bookMinLevelNullSkipsLevelQuery() {
        stubSuccessfulBook();
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, null));

        service.book(USER_ID, SLOT_ID);

        verifyNoInteractions(userLevelQuery);
    }

    @Test
    @DisplayName("book: minLevel 为 0 -> 无门槛，不查等级")
    void bookMinLevelZeroSkipsLevelQuery() {
        stubSuccessfulBook();

        service.book(USER_ID, SLOT_ID);

        verifyNoInteractions(userLevelQuery);
    }

    @Test
    @DisplayName("book: 主路径成功 -> 落 Hold 单 + 投递延迟消息 + 写 BOOK 对账日志")
    void bookSuccessMainPath() {
        stubSuccessfulBook();

        Long orderId = service.book(USER_ID, SLOT_ID);

        assertNotNull(orderId);
        ArgumentCaptor<AppointmentOrder> saved = ArgumentCaptor.forClass(AppointmentOrder.class);
        verify(orderMapper).insert(saved.capture());
        AppointmentOrder o = saved.getValue();
        assertEquals(orderId, o.getId());
        assertEquals(USER_ID, o.getUserId());
        assertEquals(SLOT_ID, o.getSlotId());
        assertEquals(5001L, o.getShopId());
        assertEquals(STAFF_ID, o.getServiceStaffId());
        assertEquals(OrderStatus.PENDING.getCode(), o.getStatus());
        assertEquals(0, o.getAmountCent());
        assertEquals("DOOR_TO_DOOR", o.getFulfillmentMode());
        assertNotNull(o.getHoldExpiresAt());
        assertTrue(o.getHoldExpiresAt().isAfter(LocalDateTime.now()));

        // Hold 窗口 10 分钟 = 600 秒
        verify(holdTimeoutTask).scheduleExpire(any(AppointmentOrder.class), eq(600L));
        verify(reconcileService).writeBestEffort(eq(orderId), eq(LogType.BOOK), eq(true),
            eq(10), eq(1), eq(9), anyLong(), eq("order created"));
        verify(luaService, never()).rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("book: orderId 与 traceId 必须分别取号（不能相同）")
    void bookOrderIdAndTraceIdAreSeparate() {
        stubSuccessfulBook();
        ArgumentCaptor<Long> orderCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> traceCaptor = ArgumentCaptor.forClass(Long.class);
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            orderCaptor.capture(), traceCaptor.capture())).thenReturn(luaResult(200));

        service.book(USER_ID, SLOT_ID);

        assertNotEquals(orderCaptor.getValue(), traceCaptor.getValue(), "orderId 与 traceId 禁止同源");
    }

    @Test
    @DisplayName("book: Lua 返回重复预约(10006) -> 抛 LUA_DUPLICATE，不落库不写对账")
    void bookLuaDuplicate() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
            .thenReturn(luaResult(10006));

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.LUA_DUPLICATE, ex.getCode());
        verify(orderMapper, never()).insert(any(AppointmentOrder.class));
        verify(holdTimeoutTask, never()).scheduleExpire(any(AppointmentOrder.class), anyLong());
        verify(reconcileService, never()).writeBestEffort(anyLong(), any(LogType.class), anyBoolean(),
            anyInt(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("book: Lua 返回余量不足(10005) -> 抛 LUA_NO_STOCK")
    void bookLuaNoStock() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
            .thenReturn(luaResult(10005));

        BaseException ex = assertThrows(BaseException.class, () -> service.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.LUA_NO_STOCK, ex.getCode());
        verify(orderMapper, never()).insert(any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("book: 落库失败 -> 回补 Redis 并把原始异常抛给调用方")
    void bookPersistFailureRollsBack() {
        stubSuccessfulBook();
        doThrow(new IllegalStateException("db down")).when(orderMapper).insert(any(AppointmentOrder.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.book(USER_ID, SLOT_ID));

        assertEquals("db down", ex.getMessage());
        verify(luaService).rollback(eq(USER_ID), eq(SLOT_ID), eq(STAFF_ID), anyLong(), anyLong(),
            anyLong(), anyLong(), eq(1));
        // 落库失败不应写 BOOK 对账日志（订单并不存在）
        verify(reconcileService, never()).writeBestEffort(anyLong(), any(LogType.class), anyBoolean(),
            anyInt(), anyInt(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("book: 延迟消息投递失败 -> 同样回补，避免余量泄漏")
    void bookScheduleFailureRollsBack() {
        stubSuccessfulBook();
        doThrow(new IllegalStateException("delay queue down")).when(holdTimeoutTask)
            .scheduleExpire(any(AppointmentOrder.class), anyLong());

        assertThrows(IllegalStateException.class, () -> service.book(USER_ID, SLOT_ID));

        verify(luaService).rollback(eq(USER_ID), eq(SLOT_ID), eq(STAFF_ID), anyLong(), anyLong(),
            anyLong(), anyLong(), eq(1));
    }

    // ------------------------------------------------------------------ queryOrder()

    @Test
    @DisplayName("queryOrder: 订单不存在 -> 抛 NOT_FOUND")
    void queryOrderMissing() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.queryOrder(USER_ID, ORDER_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("queryOrder: 越权访问他人订单 -> 统一按 NOT_FOUND，不暴露存在性")
    void queryOrderForbiddenMaskedAsNotFound() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), 9999L));

        BaseException ex = assertThrows(BaseException.class, () -> service.queryOrder(USER_ID, ORDER_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("queryOrder: 本人订单 -> 正常返回")
    void queryOrderOwn() {
        AppointmentOrder o = order(OrderStatus.PENDING.getCode(), USER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(o);

        assertSame(o, service.queryOrder(USER_ID, ORDER_ID));
    }

    // ------------------------------------------------------------------ listMyOrders()

    @Test
    @DisplayName("listMyOrders: 带状态过滤 -> 透传 Mapper 结果")
    void listMyOrdersWithStatus() {
        List<AppointmentOrder> rows = List.of(order(OrderStatus.PENDING.getCode(), USER_ID));
        when(orderMapper.selectList(any())).thenReturn(rows);

        assertSame(rows, service.listMyOrders(USER_ID, OrderStatus.PENDING.getCode()));
        verify(orderMapper).selectList(any());
    }

    @Test
    @DisplayName("listMyOrders: status 为 null -> 查全部状态")
    void listMyOrdersWithoutStatus() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listMyOrders(USER_ID, null).isEmpty());
        verify(orderMapper).selectList(any());
    }

    // ------------------------------------------------------------------ cancel()

    @Test
    @DisplayName("cancel: 待支付订单 -> 置 CANCELLED + 回补 + 写 RESTORE 日志 + 触发候补")
    void cancelPendingOrder() {
        AppointmentOrder o = order(OrderStatus.PENDING.getCode(), USER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(o);
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(200));

        service.cancel(USER_ID, ORDER_ID);

        assertEquals(OrderStatus.CANCELLED.getCode(), o.getStatus());
        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            x -> x.getStatus() == OrderStatus.CANCELLED.getCode()));
        verify(reconcileService).writeBestEffort(eq(ORDER_ID), eq(LogType.RESTORE), eq(true),
            eq(10), eq(1), eq(9), anyLong(), eq("cancel rollback"));
        verify(promotionService).promoteAsync(SLOT_ID);
    }

    @Test
    @DisplayName("cancel: 已确认订单允许取消")
    void cancelConfirmedOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.CONFIRMED.getCode(), USER_ID));
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(200));

        assertDoesNotThrow(() -> service.cancel(USER_ID, ORDER_ID));

        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>argThat(
            x -> x.getStatus() == OrderStatus.CANCELLED.getCode()));
    }

    @Test
    @DisplayName("cancel: 订单不存在 -> 抛 NOT_FOUND")
    void cancelMissingOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("cancel: 越权取消他人订单 -> 按 NOT_FOUND 处理")
    void cancelForeignOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.PENDING.getCode(), 9999L));

        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));

        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("cancel: 已完成订单不可取消 -> INTERNAL_ERROR「当前状态不可取消」")
    void cancelDoneOrderRejected() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.DONE.getCode(), USER_ID));

        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));

        assertEquals(BaseCode.INTERNAL_ERROR, ex.getCode());
        assertEquals("当前状态不可取消", ex.getMessage());
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<AppointmentOrder>any());
    }

    @Test
    @DisplayName("cancel: 已取消订单不可重复取消")
    void cancelCancelledOrderRejected() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order(OrderStatus.CANCELLED.getCode(), USER_ID));

        BaseException ex = assertThrows(BaseException.class, () -> service.cancel(USER_ID, ORDER_ID));

        assertEquals(BaseCode.INTERNAL_ERROR, ex.getCode());
    }

    @Test
    @DisplayName("cancel: 回补失败 -> 仍落日志（success=false）但不触发候补提升")
    void cancelRollbackFailedStillLogs() {
        AppointmentOrder o = order(OrderStatus.PENDING.getCode(), USER_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(o);
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 0));
        when(luaService.rollback(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(50001));

        service.cancel(USER_ID, ORDER_ID);

        verify(reconcileService).writeBestEffort(eq(ORDER_ID), eq(LogType.RESTORE), eq(false),
            anyInt(), anyInt(), anyInt(), anyLong(), eq("cancel rollback failed code=50001"));
        verify(promotionService, never()).promoteAsync(anyLong());
    }
}
