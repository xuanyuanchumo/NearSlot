package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.LogType;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.idempotent.Idempotent;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抢约编排（FR-3.1 / research/08 §3–4）。
 * 链路：幂等 → Lua 三检查预扣 → Hold 落单（待支付）→ 支付确认 / 超时回补。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {
    private final AppointmentLuaService luaService;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentOrderMapper orderMapper;
    private final DistributedLockTemplate lockTemplate;

    /** 简易雪花占位（Phase 4 换 MyBatis-Plus IdWorker，此处保证 orderId ≠ traceId）。 */
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static long nextId() { return SEQ.incrementAndGet(); }

    /** Hold 窗口：必须大于支付 P99（research/08 §4）。 */
    private static final long HOLD_MINUTES = 10;

    @Idempotent(key = "#userId + ':' + #slotId", ttl = 10)
    public Long book(Long userId, Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) throw new BaseException(BaseCode.NOT_FOUND);
        if (slot.getStatus() == null || slot.getStatus() != 1) throw new BaseException(BaseCode.SLOT_CLOSED);

        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // C-CON-03：orderId 与 traceId 分别取号，禁止假设同源
        long orderId = nextId();
        long traceId = nextId();

        BookResult r = luaService.book(userId, slotId, slot.getTechnicianId(), startTs, endTs, orderId, traceId);
        if (!r.success()) {
            throw new BaseException(BaseCode.of(r.getCode()));
        }
        try {
            persistHold(userId, slot, orderId);
        } catch (RuntimeException e) {
            // 落库失败必须回补，否则余量泄漏
            luaService.rollback(userId, slotId, slot.getTechnicianId(), startTs, orderId, traceId, 1);
            throw e;
        }
        log.info("book ok userId={} slotId={} orderId={} traceId={} before={} after={}",
            userId, slotId, orderId, traceId, r.getBeforeQty(), r.getAfterQty());
        return orderId;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void persistHold(Long userId, TimeSlot slot, long orderId) {
        AppointmentOrder o = new AppointmentOrder();
        o.setId(orderId);
        o.setUserId(userId);
        o.setSlotId(slot.getId());
        o.setShopId(slot.getShopId());
        o.setTechnicianId(slot.getTechnicianId());
        o.setStatus(OrderStatus.PENDING.getCode());
        o.setAmountCent(0);
        o.setHoldExpiresAt(LocalDateTime.now().plusMinutes(HOLD_MINUTES));
        orderMapper.insert(o);
    }

    /** 取消：状态机校验 → 回补反转 → 触发候补（异步由 MQ 承载）。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null || !o.getUserId().equals(userId)) throw new BaseException(BaseCode.NOT_FOUND);
        if (o.getStatus() != OrderStatus.PENDING.getCode() && o.getStatus() != OrderStatus.CONFIRMED.getCode()) {
            throw new BaseException(BaseCode.INTERNAL_ERROR, "当前状态不可取消");
        }
        TimeSlot slot = timeSlotMapper.selectById(o.getSlotId());
        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long traceId = nextId();

        o.setStatus(OrderStatus.CANCELLED.getCode());
        orderMapper.updateById(o);

        luaService.rollback(userId, o.getSlotId(), o.getTechnicianId(), startTs, orderId, traceId, 1);
        log.info("cancel ok userId={} orderId={} logType={} traceId={}",
            userId, orderId, LogType.RESTORE.getCode(), traceId);
    }
}
