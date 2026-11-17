package org.nearslot.appointment.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.service.SlotPublishService;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 时段可约性契约单测（跨模块 DTO 转换正确性）。 */
class SlotAvailabilityQueryImplTest {

    private SlotPublishService slotPublishService;
    private SlotAvailabilityQueryImpl impl;

    @BeforeEach
    void setUp() {
        slotPublishService = mock(SlotPublishService.class);
        impl = new SlotAvailabilityQueryImpl(slotPublishService);
    }

    private Map<String, Object> row(String id, int remaining) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("startsAt", "2026-12-01T10:00");
        m.put("endsAt", "2026-12-01T11:00");
        m.put("remaining", remaining);
        m.put("serviceStaffId", "2001");
        m.put("minLevel", 5);
        return m;
    }

    @Test
    @DisplayName("字符串 ID 正确转 Long（ADR-012 精度安全）")
    void convertsStringIds() {
        when(slotPublishService.listAvailable(1L, "2026-12-01", null))
            .thenReturn(List.of(row("123456789", 3)));

        var list = impl.available(1L, LocalDate.of(2026, 12, 1), null);

        assertEquals(1, list.size());
        assertEquals(123456789L, list.get(0).slotId());
        assertEquals(2001L, list.get(0).serviceStaffId());
        assertEquals(3, list.get(0).remain());
        assertEquals(5, list.get(0).minLevel());
    }

    @Test
    @DisplayName("空结果 -> 返回空列表（不返回 null）")
    void emptyList() {
        when(slotPublishService.listAvailable(anyLong(), any(), any())).thenReturn(List.of());
        assertTrue(impl.available(1L, null, null).isEmpty());
    }

    @Test
    @DisplayName("date 为 null -> 传 null 给 provider")
    void nullDatePassedThrough() {
        when(slotPublishService.listAvailable(1L, null, 4001L)).thenReturn(List.of());
        impl.available(1L, null, 4001L);
        verify(slotPublishService).listAvailable(1L, null, 4001L);
    }

    @Test
    @DisplayName("数字型 ID（Number）也能转换")
    void convertsNumericId() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 456L);
        m.put("startsAt", "x"); m.put("endsAt", "y");
        m.put("remaining", 1); m.put("serviceStaffId", 2002L); m.put("minLevel", 0);
        when(slotPublishService.listAvailable(anyLong(), any(), any())).thenReturn(List.of(m));

        assertEquals(456L, impl.available(1L, null, null).get(0).slotId());
    }

    @Test
    @DisplayName("非法 ID 字符串 -> 转 null 不抛异常")
    void invalidIdBecomesNull() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "not-a-number");
        m.put("startsAt", "x"); m.put("endsAt", "y");
        m.put("remaining", 1); m.put("serviceStaffId", "bad"); m.put("minLevel", 0);
        when(slotPublishService.listAvailable(anyLong(), any(), any())).thenReturn(List.of(m));

        var v = impl.available(1L, null, null).get(0);
        assertNull(v.slotId());
        assertNull(v.serviceStaffId());
    }
}
