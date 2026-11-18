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
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Hold 超时回收（FR-3.3 / research/08 §4）。
 *
 * **主路径：Redisson 延迟队列**（ADR-016 成熟库优先）。
 * 下单时投递一条延迟消息（延迟 = Hold 有效期），到期消费即回补；
 * 延迟队列数据落 Redis，进程重启不丢消息。
 *
 * **兜底：低频轮询扫描**（60s）。
 * 为何保留兜底：延迟队列消费失败或消息体解析异常时该订单会漏处理，
 * 轮询是幂等补偿（research/09b §3 指出社区版延迟队列非严格 exactly-once）。
 * 两者处理同一批订单不会重复回补——因为状态 CAS 先改状态，
 * 只有状态仍为 PENDING 的才会被处理。
 *
 * **2026-11-17 审计修复（Major）**：
 * · 顺序反了：原实现**先**置 CANCELLED **再**回补，回补失败抛异常时状态已落库，
 *   而兜底扫描只捞 PENDING —— 该订单从此无人处理，余量永久泄漏。
 *   现改为「先回补成功、再置 CANCELLED」；失败保持 PENDING 等下一轮重试。
 * · 超时回收不触发候补提升，与取消路径行为不一致，候补用户被无谓拖延。
 *   现改为回补成功后发布 {@link SlotOpenedEvent}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldTimeoutTask {

    /** 延迟队列名。 */
    public static final String HOLD_QUEUE = "nearslot:delay:hold";
    /** 兜底扫描间隔（低频，避免与业务争抢 DB）。 */
    /** 扫描周期由 `nearslot.appointment.hold-scan-interval-ms` 决定。 */

    private final AppointmentOrderMapper orderMapper;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentLuaService luaService;
    private final RedissonDelayQueue delayQueue;
    /**
     * 余量回流事件发布器。
     *
     * 为什么是事件而不是注入 PromotionService：提升需要复用抢约内核，
     * 内核又依赖本类投递 Hold 消息，直接互相注入会形成循环依赖导致启动失败。
     */
    private final ApplicationEventPublisher eventPublisher;
    /** 扫描周期等策略外置到 `nearslot.appointment.*`（调度本身用 fixedDelayString 引用同一键）。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /** 下单成功后调用：投递延迟消息（主路径）。 */
    public void scheduleExpire(AppointmentOrder order, long holdSeconds) {
        delayQueue.offer(HOLD_QUEUE, String.valueOf(order.getId()), holdSeconds);
        log.debug("Hold 超时消息已投递 orderId={} delay={}s", order.getId(), holdSeconds);
    }

    /** 启动延迟队列消费者（主路径执行入口）。 */
    public void startConsumer() {
        delayQueue.startConsumer(HOLD_QUEUE, payload -> {
            try {
                long orderId = Long.parseLong(String.valueOf(payload));
                AppointmentOrder o = orderMapper.selectById(orderId);
                if (o == null || o.getStatus() != OrderStatus.PENDING.getCode()) {
                    return;  // 已支付或已取消，忽略（幂等）
                }
                expireOne(o);
            } catch (RuntimeException e) {
                log.error("Hold 超时消费失败 payload={}", payload, e);
            }
        });
        log.info("Hold 超时延迟队列消费者已启动 queue={}", HOLD_QUEUE);
    }

    /** 兜底扫描（幂等补偿，见类注释）。 */
    @Scheduled(fixedDelayString = "${nearslot.appointment.hold-scan-interval-ms:60000}")
    public void scanExpiredHolds() {
        List<AppointmentOrder> expired = orderMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<AppointmentOrder>lambdaQuery()
                .eq(AppointmentOrder::getStatus, OrderStatus.PENDING.getCode())
                .lt(AppointmentOrder::getHoldExpiresAt, LocalDateTime.now()));
        if (expired.isEmpty()) return;
        log.info("兜底扫描发现过期 Hold {} 笔", expired.size());
        expired.forEach(o -> {
            try { expireOne(o); }
            catch (RuntimeException e) { log.error("兜底回补失败 orderId={}", o.getId(), e); }
        });
    }

    /** 单笔过期处理：先回补 Redis，成功后再置 CANCELLED。 */
    @Transactional(rollbackFor = Exception.class)
    public void expireOne(AppointmentOrder order) {
        TimeSlot slot = timeSlotMapper.selectById(order.getSlotId());
        if (slot == null) {
            log.warn("Hold 超时但时段不存在 orderId={}", order.getId());
            return;
        }
        // 幂等闸：再次确认状态，避免延迟队列与兜底扫描重复处理
        AppointmentOrder fresh = orderMapper.selectById(order.getId());
        if (fresh == null || fresh.getStatus() != OrderStatus.PENDING.getCode()) return;

        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int capacity = slot.getCapacity() == null ? 1 : slot.getCapacity();

        BookResult r = luaService.rollback(fresh.getUserId(), fresh.getSlotId(),
            fresh.getServiceStaffId(), startTs, endTs, fresh.getId(), IdGenerator.nextId(), 1, capacity);

        if (!r.success()) {
            log.error("Hold 超时回补失败，订单保持 PENDING 待下一轮重试 orderId={} code={}",
                fresh.getId(), r.getCode());
            throw new IllegalStateException("回补失败 code=" + r.getCode());
        }

        fresh.setStatus(OrderStatus.CANCELLED.getCode());
        orderMapper.updateById(fresh);
        log.info("Hold 超时回补成功 orderId={} logType={}", fresh.getId(), LogType.RESTORE.getCode());

        // 余量已回流：发布事件，由 PromotionService 异步提升候补（与取消路径行为一致）
        eventPublisher.publishEvent(new SlotOpenedEvent(fresh.getSlotId()));
    }
}
