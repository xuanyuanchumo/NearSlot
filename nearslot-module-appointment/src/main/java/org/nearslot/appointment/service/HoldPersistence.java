package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.config.AppointmentProperties;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.common.enums.OrderStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Hold 落单事务执行器（评审 B-001）。
 *
 * 为什么单独成 Bean：SlotBookingCore.book() 原先同类自调用 persistHold()，
 * 不经过 Spring 代理，@Transactional 完全失效——落库失败时无事务回滚，
 * 依赖外层手工回补。拆成独立 Bean 后，book() 通过代理调用，事务真实生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldPersistence {

    private final AppointmentOrderMapper orderMapper;
    private final AppointmentProperties props;

    @Transactional(rollbackFor = Exception.class)
    public void persistHold(Long userId, TimeSlot slot, Long orderId) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(orderId);
        o.setUserId(userId);
        o.setSlotId(slot.getId());
        o.setShopId(slot.getShopId());
        o.setServiceStaffId(slot.getServiceStaffId());
        o.setFulfillmentMode("DOOR_TO_DOOR");
        o.setStatus(OrderStatus.PENDING.getCode());
        o.setAmountCent(0);
        o.setHoldExpiresAt(LocalDateTime.now().plusMinutes(props.getHoldMinutes()));
        orderMapper.insert(o);
        log.debug("Hold 落单完成 orderId={} userId={}", orderId, userId);
    }
}
