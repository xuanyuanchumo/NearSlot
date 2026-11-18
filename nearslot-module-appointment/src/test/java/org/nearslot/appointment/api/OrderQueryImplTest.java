package org.nearslot.appointment.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.service.AppointmentService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 订单查询契约单测（含归属校验与状态文案）。 */
class OrderQueryImplTest {

    private AppointmentService appointmentService;
    private AppointmentOrderMapper orderMapper;
    private OrderQueryImpl impl;

    @BeforeEach
    void setUp() {
        appointmentService = mock(AppointmentService.class);
        orderMapper = mock(AppointmentOrderMapper.class);
        impl = new OrderQueryImpl(appointmentService, orderMapper);
    }

    private AppointmentOrder order(int status) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(100L); o.setSlotId(1L); o.setShopId(1L);
        o.setUserId(1001L);
        o.setServiceStaffId(2001L); o.setStatus(status); o.setAmountCent(9900);
        o.setFulfillmentMode("DOOR_TO_DOOR");
        return o;
    }

    @Test
    @DisplayName("归属校验通过 -> 返回视图")
    void byIdOwned() {
        when(orderMapper.selectById(100L)).thenReturn(order(2));
        var v = impl.byId(1001L, 100L);
        assertNotNull(v);
        assertEquals(100L, v.orderId());
        assertEquals("已确认", v.statusText());
    }

    @Test
    @DisplayName("归属不符 -> 返回 null（不泄露订单存在性）")
    void byIdNotOwned() {
        when(orderMapper.selectById(100L)).thenReturn(order(2));
        // 归属不符：order.userId=1001L，调用者是 2002L -> null
        assertNull(impl.byId(2002L, 100L));
    }

    @Test
    @DisplayName("查无此单 -> null")
    void byIdMissing() {
        when(orderMapper.selectById(anyLong())).thenReturn(null);
        assertNull(impl.byId(1L, 2L));
    }

    @Test
    @DisplayName("列表查询映射状态文案")
    void listByUserMapsStatus() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(1), order(3), order(4), order(5)));
        var list = impl.listByUser(1001L, 10);
        assertEquals(4, list.size());
        assertEquals("待支付", list.get(0).statusText());
        assertEquals("已取消", list.get(1).statusText());
        assertEquals("已完成", list.get(2).statusText());
        assertEquals("已退款", list.get(3).statusText());
    }

    @Test
    @DisplayName("未知状态码 -> 未知文案（不抛异常）")
    void unknownStatus() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(99)));
        assertEquals("未知", impl.listByUser(1L, 5).get(0).statusText());
    }

    @Test
    @DisplayName("status 为 null -> 0 + 未知")
    void nullStatus() {
        AppointmentOrder o = order(0);
        o.setStatus(null);
        when(orderMapper.selectList(any())).thenReturn(List.of(o));
        var v = impl.listByUser(1L, 5).get(0);
        assertEquals(0, v.status());
        assertEquals("未知", v.statusText());
    }
}
