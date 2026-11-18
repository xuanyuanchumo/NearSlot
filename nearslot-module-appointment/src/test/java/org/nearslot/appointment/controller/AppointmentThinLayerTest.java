package org.nearslot.appointment.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.api.AppointmentBookingImpl;
import org.nearslot.appointment.api.TechRankQueryImpl;
import org.nearslot.appointment.service.AppointmentService;
import org.nearslot.appointment.service.TechRankService;
import org.nearslot.appointment.service.WaitlistService;
import org.nearslot.common.api.TechRankQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 薄封装层单测：控制器与 common 契约实现。
 *
 * 这些类本身几乎只有一行转发，但它们是**契约边界**——
 * 一旦参数顺序/语义错位（例如 agent 传的 shopId 被忽略、date 兜底逻辑丢失），
 * 业务侧单元测试全部发现不了。因此显式锁住转发语义。
 */
class AppointmentThinLayerTest {

    @Test
    @DisplayName("TechRankController: date 为 null 时用今天；limit 透传")
    void rankControllerDefaultsDate() {
        TechRankService svc = mock(TechRankService.class);
        when(svc.top(anyLong(), any(), anyInt())).thenReturn(List.of());
        TechRankController controller = new TechRankController(svc);

        controller.top(1L, null, 5);

        verify(svc).top(eq(1L), eq(LocalDate.now()), eq(5));
    }

    @Test
    @DisplayName("TechRankController: 显式 date 透传")
    void rankControllerPassesDate() {
        TechRankService svc = mock(TechRankService.class);
        LocalDate d = LocalDate.of(2026, 12, 1);
        when(svc.top(anyLong(), any(), anyInt())).thenReturn(List.of());
        TechRankController controller = new TechRankController(svc);

        controller.top(1L, d, 5);

        verify(svc).top(eq(1L), eq(d), eq(5));
    }

    @Test
    @DisplayName("AppointmentBookingImpl: book/cancel/subscribe 全部转发到业务服务")
    void bookingImplForwards() {
        AppointmentService appointmentService = mock(AppointmentService.class);
        WaitlistService waitlistService = mock(WaitlistService.class);
        when(appointmentService.book(1L, 2L)).thenReturn(9L);
        when(waitlistService.join(2L, 1L)).thenReturn(3L);
        AppointmentBookingImpl impl = new AppointmentBookingImpl(appointmentService, waitlistService);

        assertEquals(9L, impl.book(1L, 2L));
        impl.cancel(1L, 9L);
        assertEquals(3L, impl.subscribeSlot(1L, 2L));

        verify(appointmentService).cancel(1L, 9L);
        // 注意参数顺序：waitlist.join(slotId, userId)，与 book(userId, slotId) 相反
        verify(waitlistService).join(2L, 1L);
    }

    @Test
    @DisplayName("TechRankQueryImpl: date 为 null 兜底今天，Map 字段缺失时不 NPE")
    void rankQueryImplNormalizes() {
        TechRankService svc = mock(TechRankService.class);
        when(svc.top(anyLong(), any(), anyInt())).thenReturn(List.of(
            Map.of("rank", 1, "serviceStaffId", 2001L, "deals", 5),
            Map.of()  // 缺字段的行不能抛异常
        ));
        TechRankQueryImpl impl = new TechRankQueryImpl(svc);

        List<TechRankQuery.RankEntry> rows = impl.top(1L, null, 2);

        assertEquals(2, rows.size());
        assertEquals(2001L, rows.get(0).serviceStaffId());
        assertEquals(5, rows.get(0).deals());
        assertEquals(0, rows.get(1).deals());
        verify(svc).top(eq(1L), eq(LocalDate.now()), eq(2));
    }
}
