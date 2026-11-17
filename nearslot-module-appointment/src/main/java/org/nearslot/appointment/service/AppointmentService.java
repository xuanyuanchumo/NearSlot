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
    private final HoldTimeoutTask holdTimeoutTask;
    private final ReconcileService reconcileService;
    /** 等级校验走 common 接口，避免 appointment 直接依赖 auth 模块（C-ARCH-01）。 */
    private final org.nearslot.common.api.UserLevelQuery userLevelQuery;
    /** 候补提升：取消/回补后触发（FR-3.2）。 */
    private final PromotionService promotionService;

    /**
     * 分布式 ID：直接用 MyBatis-Plus 的 IdWorker（成熟库优先，ADR-016）。
     * 此前手写 AtomicLong 是造轮子：单机自增、无 workerId 隔离、重启易撞号。
     * IdWorker 自带雪花算法（时间戳+数据中心+机器+序列），且保证多次调用不重复。
     */
    private static long nextId() { return org.nearslot.framework.id.IdGenerator.nextId(); }

    /** Hold 窗口：必须大于支付 P99（research/08 §4）。 */
    private static final long HOLD_MINUTES = 10;

    @Idempotent(key = "#userId + ':' + #slotId", ttl = 10)
    public Long book(Long userId, Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) throw new BaseException(BaseCode.NOT_FOUND);
        if (slot.getStatus() == null || slot.getStatus() != 1) throw new BaseException(BaseCode.SLOT_CLOSED);

        // FR-1.4 会员等级闸：等级不足直接拒绝（在 Lua 之前短路，避免无效 Redis 写入）
        int minLevel = slot.getMinLevel() == null ? 0 : slot.getMinLevel();
        if (minLevel > 0) {
            int userLevel = userLevelQuery.levelOf(userId);
            if (userLevel < minLevel) {
                throw new BaseException(BaseCode.LEVEL_NOT_MET,
                    "需 Lv" + minLevel + " 及以上会员，当前 Lv" + userLevel);
            }
        }

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
            // 主路径：投递 Hold 超时延迟消息（成熟库优先，ADR-016）
            AppointmentOrder created = new AppointmentOrder();
            created.setId(orderId);
            holdTimeoutTask.scheduleExpire(created, HOLD_MINUTES * 60);
        } catch (RuntimeException e) {
            // 落库失败必须回补，否则余量泄漏
            luaService.rollback(userId, slotId, slot.getTechnicianId(), startTs, endTs, orderId, traceId, 1);
            throw e;
        }
        // 对账日志（best-effort）：写入失败不影响下单，但对账任务会因此判 ABNORMAL，
        // 所以这里必须写，否则 ABNORMAL 永远无法归零（Phase 2 出口标准）。
        reconcileService.writeBestEffort(orderId, LogType.BOOK, true,
            r.getBeforeQty(), r.getDeductQty(), r.getAfterQty(), traceId, "order created");

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

    /** 订单详情（FR-4.1）：校验归属防越权。 */
    public AppointmentOrder queryOrder(Long userId, Long orderId) {
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null) throw new BaseException(BaseCode.NOT_FOUND);
        if (!o.getUserId().equals(userId)) {
            // 越权访问：不暴露订单是否存在，统一按 NOT_FOUND
            throw new BaseException(BaseCode.NOT_FOUND);
        }
        return o;
    }

    /** 我的订单列表（FR-4.1），按创建时间倒序。 */
    public java.util.List<AppointmentOrder> listMyOrders(Long userId, Integer status) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<AppointmentOrder>lambdaQuery()
            .eq(AppointmentOrder::getUserId, userId);
        if (status != null) q.eq(AppointmentOrder::getStatus, status);
        q.orderByDesc(AppointmentOrder::getId).last("LIMIT 50");
        return orderMapper.selectList(q);
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
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long traceId = nextId();

        o.setStatus(OrderStatus.CANCELLED.getCode());
        orderMapper.updateById(o);

        BookResult rb = luaService.rollback(userId, o.getSlotId(), o.getTechnicianId(),
            startTs, endTs, orderId, traceId, 1);
        // 回补结果同样入对账日志（成功/失败都记，便于三方互证）
        reconcileService.writeBestEffort(orderId, LogType.RESTORE, rb.success(),
            rb.getBeforeQty(), rb.getDeductQty(), rb.getAfterQty(), traceId,
            rb.success() ? "cancel rollback" : "cancel rollback failed code=" + rb.getCode());
        log.info("cancel ok userId={} orderId={} logType={} traceId={} rollbackSuccess={}",
            userId, orderId, LogType.RESTORE.getCode(), traceId, rb.success());

        // FR-3.2 余量回流：取消后异步提升队首候补（不阻塞取消端点）
        if (rb.success()) {
            promotionService.promoteAsync(o.getSlotId());
        }
    }
}
