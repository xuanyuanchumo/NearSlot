package org.nearslot.appointment.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.OrderStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CompletedOrderQueryImpl 单测（FR-7/12）：履约驱动校验（BL-TEST-01 覆盖率补齐）。 */
class CompletedOrderQueryImplTest {

    private AppointmentOrderMapper orderMapper;
    private TimeSlotMapper slotMapper;
    private CompletedOrderQueryImpl query;

    @BeforeEach
    void setUp() {
        orderMapper = mock(AppointmentOrderMapper.class);
        slotMapper = mock(TimeSlotMapper.class);
        query = new CompletedOrderQueryImpl(orderMapper, slotMapper);
    }

    private AppointmentOrder order(int status) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(9001L);
        o.setUserId(1001L);
        o.setShopId(1L);
        o.setSlotId(3001L);
        o.setServiceStaffId(2001L);
        o.setStatus(status);
        o.setUpdatedAt(LocalDateTime.now());
        return o;
    }

    @Test
    @DisplayName("已完成订单 -> 返回 ReviewableOrder（含 serviceItemId）")
    void completedOrder() {
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.DONE.getCode()));
        TimeSlot s = new TimeSlot();
        s.setId(3001L);
        s.setServiceItemId(42L);
        when(slotMapper.selectById(3001L)).thenReturn(s);

        var r = query.reviewableOf(1001L, 9001L);

        assertNotNull(r);
        assertEquals(1L, r.shopId());
        assertEquals(2001L, r.technicianId());
        assertEquals(42L, r.serviceItemId());
    }

    @Test
    @DisplayName("非本人订单 -> null（越权防护）")
    void notOwner() {
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.DONE.getCode()));
        assertNull(query.reviewableOf(9999L, 9001L));
    }

    @Test
    @DisplayName("未完成订单 -> null")
    void notCompleted() {
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.PENDING.getCode()));
        assertNull(query.reviewableOf(1001L, 9001L));
    }

    @Test
    @DisplayName("订单不存在 -> null；null 参数 -> null")
    void missing() {
        when(orderMapper.selectById(9001L)).thenReturn(null);
        assertNull(query.reviewableOf(1001L, 9001L));
        assertNull(query.reviewableOf(null, 9001L));
        assertNull(query.reviewableOf(1001L, null));
    }

    @Test
    @DisplayName("时段缺失 -> serviceItemId 为 null（仍返回可评价订单）")
    void slotMissing() {
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.DONE.getCode()));
        when(slotMapper.selectById(3001L)).thenReturn(null);
        var r = query.reviewableOf(1001L, 9001L);
        assertNotNull(r);
        assertNull(r.serviceItemId());
    }

    @Test
    @DisplayName("shopIdOf: 订单存在取店铺，否则 null")
    void shopIdOf() {
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.DONE.getCode()));
        assertEquals(1L, query.shopIdOf(9001L));
        assertNull(query.shopIdOf(null));
        when(orderMapper.selectById(9999L)).thenReturn(null);
        assertNull(query.shopIdOf(9999L));
    }

    @Test
    @DisplayName("isCompletedBy 与 reviewableOf 语义一致")
    void isCompletedBy() {
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.DONE.getCode()));
        assertTrue(query.isCompletedBy(1001L, 9001L));
        assertFalse(query.isCompletedBy(9999L, 9001L));
    }
}
