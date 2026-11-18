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
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 抢约内核单测（Lua 预扣 + 落单 + 对账日志）。
 *
 * 覆盖分支：时段不存在 / 时段关闭 / Lua 各失败码透传 / 落库失败必须回补 /
 * 对账日志写入 / 成功路径的请求参数（capacity、startTs、endTs）。
 *
 * 依赖全部 Mock，不连真实 Redis/MySQL；Lua 语义由 AppointmentLuaIntegrationTest（真实 Redis）覆盖。
 */
class SlotBookingCoreTest {

    private static final long SLOT_ID = 2001L;
    private static final long USER_ID = 3001L;
    private static final long STAFF_ID = 4001L;

    private AppointmentLuaService luaService;
    private TimeSlotMapper timeSlotMapper;
    private AppointmentOrderMapper orderMapper;
    private ReconcileService reconcileService;
    private HoldTimeoutTask holdTimeoutTask;
    private SlotBookingCore core;

    @BeforeEach
    void setUp() {
        luaService = mock(AppointmentLuaService.class);
        timeSlotMapper = mock(TimeSlotMapper.class);
        orderMapper = mock(AppointmentOrderMapper.class);
        reconcileService = mock(ReconcileService.class);
        holdTimeoutTask = mock(HoldTimeoutTask.class);
        core = new SlotBookingCore(luaService, timeSlotMapper, orderMapper, reconcileService,
            holdTimeoutTask, new org.nearslot.appointment.config.AppointmentProperties());
    }

    private TimeSlot slot(Integer status, int capacity) {
        TimeSlot s = new TimeSlot();
        s.setId(SLOT_ID);
        s.setShopId(5001L);
        s.setServiceStaffId(STAFF_ID);
        s.setStatus(status);
        s.setCapacity(capacity);
        s.setMinLevel(0);
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

    @Test
    @DisplayName("book: 时段不存在 -> NOT_FOUND，不调 Lua")
    void slotMissing() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class, () -> core.book(USER_ID, SLOT_ID));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("book: 时段关闭（status!=1）-> SLOT_CLOSED，不调 Lua")
    void slotClosed() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(2, 1));
        BaseException ex = assertThrows(BaseException.class, () -> core.book(USER_ID, SLOT_ID));
        assertEquals(BaseCode.SLOT_CLOSED, ex.getCode());
        verifyNoInteractions(luaService);
    }

    @Test
    @DisplayName("book: status 为 null -> SLOT_CLOSED（防御空状态）")
    void slotStatusNull() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(null, 1));
        BaseException ex = assertThrows(BaseException.class, () -> core.book(USER_ID, SLOT_ID));
        assertEquals(BaseCode.SLOT_CLOSED, ex.getCode());
    }

    @Test
    @DisplayName("book: Lua 返回 10005 库存不足 -> 业务码透传，不落单")
    void luaNoStock() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 1));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(10005));

        BaseException ex = assertThrows(BaseException.class, () -> core.book(USER_ID, SLOT_ID));

        assertEquals(10005, ex.getCode().getCode());
        verify(orderMapper, never()).insert(any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("book: Lua 返回 10006 重复预约 -> 50102 语义透传，不落单")
    void luaDuplicate() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 1));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(10006));

        BaseException ex = assertThrows(BaseException.class, () -> core.book(USER_ID, SLOT_ID));

        assertEquals(BaseCode.LUA_DUPLICATE, ex.getCode());
        verify(orderMapper, never()).insert(any(AppointmentOrder.class));
    }

    @Test
    @DisplayName("book: 成功 -> 落 PENDING 单 + 写 BOOK 对账日志 + 传 capacity 给 Lua")
    void bookSuccess() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 5));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(200));

        Long orderId = core.book(USER_ID, SLOT_ID);

        assertNotNull(orderId);
        ArgumentCaptor<AppointmentOrder> captor = ArgumentCaptor.forClass(AppointmentOrder.class);
        verify(orderMapper).insert(captor.capture());
        AppointmentOrder saved = captor.getValue();
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(SLOT_ID, saved.getSlotId());
        assertEquals(STAFF_ID, saved.getServiceStaffId());
        assertEquals(OrderStatus.PENDING.getCode(), saved.getStatus());
        assertNotNull(saved.getHoldExpiresAt());
        // capacity 必须传给 Lua（余量键缺失时脚本用它兜底重建，否则该时段永久不可约）
        verify(luaService).book(eq(USER_ID), eq(SLOT_ID), eq(STAFF_ID), anyLong(), anyLong(),
            anyLong(), anyLong(), eq(5));
        verify(reconcileService).writeBestEffort(anyLong(), any(), eq(true), anyInt(), anyInt(),
            anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("book: 落库失败 -> 必须回补 Redis（否则余量泄漏）并重抛")
    void bookPersistFailureRollsBack() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 3));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(200));
        doThrow(new IllegalStateException("DB down")).when(orderMapper).insert(any(AppointmentOrder.class));

        assertThrows(IllegalStateException.class, () -> core.book(USER_ID, SLOT_ID));

        // 回补必须带 capacity（INCR 后封顶，防超卖）
        verify(luaService).rollback(eq(USER_ID), eq(SLOT_ID), eq(STAFF_ID), anyLong(), anyLong(),
            anyLong(), anyLong(), eq(1), eq(3));
    }

    @Test
    @DisplayName("holdSeconds: 与 Hold 窗口常量一致（10 分钟）")
    void holdSeconds() {
        assertEquals(600L, core.holdSeconds());
    }

    @Test
    @DisplayName("book 成功 -> 必须投递 Hold 超时延迟消息（否则余量永不回收）")
    void bookSchedulesHoldExpiry() {
        when(timeSlotMapper.selectById(SLOT_ID)).thenReturn(slot(1, 2));
        when(luaService.book(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
            anyLong(), anyInt())).thenReturn(luaResult(200));

        core.book(USER_ID, SLOT_ID);

        verify(holdTimeoutTask).scheduleExpire(any(AppointmentOrder.class), eq(600L));
    }
}
