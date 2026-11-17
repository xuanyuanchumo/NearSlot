package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.LogType;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.id.IdGenerator;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

/**
 * 支付回调（FR-4.2）。
 *
 * **幂等**：同一笔订单重复回调只生效一次 —— 用订单状态机做幂等，
 * 只有 PENDING 才能转 CONFIRMED，其余状态直接返回成功（幂等应答），
 * 避免支付网关重试导致重复入账。
 *
 * **与 Hold 超时的竞态**（research/08 §4 特色分支）：
 * 支付回调与 Hold 超时可能同时到达，用分布式锁串行化，
 * 锁内重新读取状态再判定 —— 谁先拿到锁谁生效，另一方看到状态已变则放弃。
 *
 * **订单已取消但支付成功**：说明用户在被超时回收后又完成支付，
 * 此时必须**退款**而非强行确认（否则会超卖）。此分支记录 RESTORE 对账日志并返回需退款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PAY_LOCK = RedisConstants.LOCK + "pay:";

    /**
     * 准备提前量（ADR-022）。v1 无履约项关联表，默认 0（不提醒）；
     * v2 接入 appointment_item 后从履约项 service_item.lead_time_min 读取。
     */
    private int resolveLeadTime(AppointmentOrder order) { return 0; }

    private final AppointmentOrderMapper orderMapper;
    private final TimeSlotMapper timeSlotMapper;
    private final ReconcileService reconcileService;
    private final DistributedLockTemplate lockTemplate;
    private final StringRedisTemplate redis;
    private final TechRankService techRankService;
    private final PrepareReminderService prepareReminderService;

    /**
     * 支付成功回调。
     *
     * @param orderId   订单号
     * @param payNo     支付流水号（网关侧）
     * @return 处理结果
     */
    public PayResult onPaid(Long orderId, String payNo) {
        return lockTemplate.withLock(PAY_LOCK + orderId, 3_000L, 10_000L, () -> doOnPaid(orderId, payNo));
    }

    @Transactional(rollbackFor = Exception.class)
    protected PayResult doOnPaid(Long orderId, String payNo) {
        AppointmentOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BaseException(BaseCode.NOT_FOUND, "订单不存在");
        }

        // 幂等：已是终态直接应答成功，不重复处理
        int st = order.getStatus();
        if (st == OrderStatus.CONFIRMED.getCode() || st == OrderStatus.DONE.getCode()) {
            log.info("重复支付回调，幂等应答 orderId={} payNo={}", orderId, payNo);
            return PayResult.IDEMPOTENT;
        }

        // 已取消（Hold 超时已回收）：钱已收但资源已释放 → 必须退款
        if (st == OrderStatus.CANCELLED.getCode()) {
            log.error("订单已取消但收到支付，需退款 orderId={} payNo={}", orderId, payNo);
            // 标记退款状态，等待退款流程
            AppointmentOrder upd = new AppointmentOrder();
            upd.setId(orderId);
            upd.setStatus(OrderStatus.REFUNDED.getCode());
            orderMapper.updateById(upd);
            reconcileService.writeBestEffort(orderId, LogType.RESTORE, true,
                0, 0, 0, IdGenerator.nextId(), "paid after cancelled, refund needed payNo=" + payNo);
            return PayResult.NEED_REFUND;
        }

        if (st != OrderStatus.PENDING.getCode()) {
            throw new BaseException(BaseCode.INTERNAL_ERROR, "订单状态异常: " + st);
        }

        // 正常：PENDING → CONFIRMED
        AppointmentOrder upd = new AppointmentOrder();
        upd.setId(orderId);
        upd.setStatus(OrderStatus.CONFIRMED.getCode());
        orderMapper.updateById(upd);

        // 清掉 Hold 过期时间（已支付不再需要超时回收）
        TimeSlot slot = timeSlotMapper.selectById(order.getSlotId());
        if (slot != null) {
            long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            // 标记支付完成：从延迟队列的语义上已不会再被回补（状态非 PENDING 即被消费端忽略）
            log.info("支付成功 orderId={} payNo={} startTs={}", orderId, payNo, startTs);
        }
        reconcileService.writeBestEffort(orderId, LogType.BOOK, true,
            0, 0, 0, IdGenerator.nextId(), "paid payNo=" + payNo);

        // FR-4.3：支付成功才计入热门技师榜（避免取消/超时造成虚高）
        techRankService.recordDeal(order.getShopId(), order.getServiceStaffId(),
            java.time.LocalDate.now());

        // ADR-022：店铺准备提醒（v1 默认 lead=0 不提醒，店铺配置后才生效）
        // 复用上方已加载的 slot（timeSlotMapper），避免重复查询
        if (slot != null) {
            prepareReminderService.schedule(orderId, slot.getStartsAt(),
                resolveLeadTime(order));
        }

        return PayResult.CONFIRMED;
    }

    /** 支付回调结果。 */
    public enum PayResult {
        /** 首次支付成功，订单转 CONFIRMED。 */
        CONFIRMED,
        /** 重复回调，幂等应答。 */
        IDEMPOTENT,
        /** 订单已取消但收到支付，需退款。 */
        NEED_REFUND
    }
}
