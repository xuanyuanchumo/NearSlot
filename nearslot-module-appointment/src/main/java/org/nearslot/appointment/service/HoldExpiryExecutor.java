package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.event.SlotOpenedEvent;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.LogType;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

/**
 * Hold 超时回补事务执行器（评审 B-001）。
 *
 * HoldTimeoutTask 的延迟消费回调与 @Scheduled 兜底扫描都是同类自调用 expireOne()，
 * 不经过 Spring 代理导致 @Transactional 失效：回补 Redis 成功但置 CANCELLED 失败时
 * 事务无法回滚。拆成独立 Bean 后事务边界真实生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryExecutor {

    private final AppointmentOrderMapper orderMapper;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentLuaService luaService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public void expireOne(AppointmentOrder order) {
        TimeSlot slot = timeSlotMapper.selectById(order.getSlotId());
        if (slot == null) {
            log.warn("Hold 超时但时段不存在 orderId={}", order.getId());
            return;
        }
        AppointmentOrder fresh = orderMapper.selectById(order.getId());
        if (fresh == null || fresh.getStatus() != OrderStatus.PENDING.getCode()) return;

        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int capacity = slot.getCapacity() == null ? 1 : slot.getCapacity();

        BookResult r = luaService.rollback(fresh.getUserId(), fresh.getSlotId(),
            fresh.getServiceStaffId(), startTs, endTs, fresh.getId(), IdGenerator.nextId(), 1, capacity);
        if (!r.success()) {
            log.error("Hold 超时回补失败 orderId={} code={}", fresh.getId(), r.getCode());
            throw new IllegalStateException("回补失败 code=" + r.getCode());
        }

        fresh.setStatus(OrderStatus.CANCELLED.getCode());
        orderMapper.updateById(fresh);
        log.info("Hold 超时回补成功 orderId={} logType={}", fresh.getId(), LogType.RESTORE.getCode());
        eventPublisher.publishEvent(new SlotOpenedEvent(fresh.getSlotId()));
    }
}
