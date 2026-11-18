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
import org.nearslot.framework.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 抢约内核（Lua 预扣 + 落单 + Hold 排期 + 对账日志）。
 *
 * **为什么单独抽成一个 Bean（2026-11-17 审计，Blocker）**：
 * 1. 候补提升此前只改 Redis 三键、把用户标成 SUCCESS，
 *    却**不创建订单也不扣库存**——被"提升"的用户在"我的预约"里什么也看不到，
 *    这是 FR-3.2 的实质性断链。提升必须复用与抢约完全相同的预扣+落单链路。
 * 2. {@code AppointmentService} 已依赖 PromotionService，PromotionService 不能反向依赖它
 *    （会形成循环依赖）；因此把共用内核下沉到本 Bean。
 * 3. 事务：本类是独立 Bean、方法 public，@Transactional 才真正生效
 *    （原 AppointmentService#persistHold 是 protected 且同类自调用，事务形同虚设）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotBookingCore {


    private final AppointmentLuaService luaService;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentOrderMapper orderMapper;
    private final ReconcileService reconcileService;
    private final HoldTimeoutTask holdTimeoutTask;
    /** 评审 B-001：落单事务必须在独立 Bean，经 Spring 代理调用才生效。 */
    private final HoldPersistence holdPersistence;
    /** Hold 窗口等策略外置到 `nearslot.appointment.*`（必须大于支付 P99，research/08 §4）。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /**
     * 执行一次抢约并投递 Hold 超时消息。
     *
     * @return 新订单号
     * @throws BaseException 时段不存在/已关闭，或 Lua 检查拒绝（码透传）
     */
    public Long book(Long userId, Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) throw new BaseException(BaseCode.NOT_FOUND);
        if (slot.getStatus() == null || slot.getStatus() != 1) throw new BaseException(BaseCode.SLOT_CLOSED);

        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int capacity = slot.getCapacity() == null ? 1 : slot.getCapacity();

        // C-CON-03：orderId 与 traceId 分别取号，禁止假设同源
        long orderId = IdGenerator.nextId();
        long traceId = IdGenerator.nextId();

        BookResult r = luaService.book(userId, slotId, slot.getServiceStaffId(),
            startTs, endTs, orderId, traceId, capacity);
        if (!r.success()) {
            throw new BaseException(BaseCode.of(r.getCode()));
        }
        try {
            // 评审 B-001：经独立 Bean 调用，@Transactional 才真正生效（同类自调用不经过代理）
            holdPersistence.persistHold(userId, slot, orderId);
            // 主路径：投递 Hold 超时延迟消息（幂等回收由 HoldTimeoutTask 保证）
            AppointmentOrder created = new AppointmentOrder();
            created.setId(orderId);
            holdTimeoutTask.scheduleExpire(created, holdSeconds());
        } catch (RuntimeException e) {
            // 落库/排期失败必须回补，否则余量泄漏
            luaService.rollback(userId, slotId, slot.getServiceStaffId(), startTs, endTs,
                orderId, traceId, 1, capacity);
            throw e;
        }
        // 对账日志（best-effort）：写入失败不影响下单，但对账任务会因此判 ABNORMAL
        reconcileService.writeBestEffort(orderId, LogType.BOOK, true,
            r.getBeforeQty(), r.getDeductQty(), r.getAfterQty(), traceId, "order created");

        log.info("book ok userId={} slotId={} orderId={} traceId={} before={} after={}",
            userId, slotId, orderId, traceId, r.getBeforeQty(), r.getAfterQty());
        return orderId;
    }

    /** Hold 窗口秒数（供延迟队列排期）。 */
    public long holdSeconds() {
        return props.getHoldMinutes() * 60L;
    }
}
