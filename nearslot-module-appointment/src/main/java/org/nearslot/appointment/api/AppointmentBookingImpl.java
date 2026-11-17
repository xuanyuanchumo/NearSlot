package org.nearslot.appointment.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.AppointmentService;
import org.nearslot.appointment.service.WaitlistService;
import org.nearslot.common.api.AppointmentBooking;
import org.springframework.stereotype.Service;

/**
 * 预约命令实现（薄封装）。
 *
 * 业务校验全部下沉到 AppointmentService（Lua 三检查/状态机/对账），
 * agent 侧不做重复判断——design §9.4 明确要求。
 */
@Service
@RequiredArgsConstructor
public class AppointmentBookingImpl implements AppointmentBooking {

    private final AppointmentService appointmentService;
    private final WaitlistService waitlistService;

    @Override
    public Long book(Long userId, Long slotId) {
        return appointmentService.book(userId, slotId);
    }

    @Override
    public void cancel(Long userId, Long orderId) {
        appointmentService.cancel(userId, orderId);
    }

    @Override
    public long subscribeSlot(Long userId, Long slotId) {
        return waitlistService.join(slotId, userId);
    }
}
