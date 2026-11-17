package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 准备提醒单测（ADR-022：按履约时间倒推，已过点立即触发）。 */
class PrepareReminderServiceTest {

    private RedissonDelayQueue delayQueue;
    private AppointmentOrderMapper orderMapper;
    private PrepareReminderService service;

    @BeforeEach
    void setUp() {
        delayQueue = mock(RedissonDelayQueue.class);
        orderMapper = mock(AppointmentOrderMapper.class);
        service = new PrepareReminderService(delayQueue, orderMapper);
    }

    @Test
    @DisplayName("履约时间在未来 -> 投递延迟消息")
    void schedulesWhenFuture() {
        service.schedule(1L, LocalDateTime.now().plusHours(2), 15);
        verify(delayQueue).offer(eq(PrepareReminderService.PREPARE_QUEUE), eq("1"), anyLong());
    }

    @Test
    @DisplayName("已过提醒点 -> 立即触发（不丢弃）")
    void firesImmediatelyWhenOverdue() {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(1L); o.setShopId(1L); o.setServiceStaffId(2001L);
        o.setFulfillmentMode("PICKUP");
        when(orderMapper.selectById(1L)).thenReturn(o);

        service.schedule(1L, LocalDateTime.now().plusMinutes(5), 30);

        verify(delayQueue, never()).offer(anyString(), any(), anyLong());
        verify(orderMapper).selectById(1L);
    }

    @Test
    @DisplayName("lead_time <= 0 -> 不提醒（店铺未配置）")
    void skipsWhenNoLeadTime() {
        service.schedule(1L, LocalDateTime.now().plusHours(1), 0);
        verifyNoInteractions(delayQueue);
    }

    @Test
    @DisplayName("履约时间为 null -> 不提醒")
    void skipsWhenNoPerformAt() {
        service.schedule(1L, null, 15);
        verifyNoInteractions(delayQueue);
    }

    @Test
    @DisplayName("fire: 订单不存在 -> 静默返回")
    void fireMissingOrder() {
        when(orderMapper.selectById(9L)).thenReturn(null);
        assertDoesNotThrow(() -> service.fire(9L));
    }
}
