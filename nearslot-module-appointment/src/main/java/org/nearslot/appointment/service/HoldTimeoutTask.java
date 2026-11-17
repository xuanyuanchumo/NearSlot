package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.LogType;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;
import org.nearslot.framework.id.IdGenerator;
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
 * 两者处理同一批订单不会重复回补——因为状态机先 CAS 置 CANCELLED，
 * 只有状态仍为 PENDING 的才会被处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldTimeoutTask {

    /** 延迟队列名。 */
    public static final String HOLD_QUEUE = "nearslot:delay:hold";
    /** 兜底扫描间隔（低频，避免与业务争抢 DB）。 */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    private final AppointmentOrderMapper orderMapper;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentLuaService luaService;
    private final RedissonDelayQueue delayQueue;

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
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
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

    /** 单笔过期处理：状态 CAS 置 CANCELLED + 回补 Redis（反转语义）。 */
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

        fresh.setStatus(OrderStatus.CANCELLED.getCode());
        orderMapper.updateById(fresh);

        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        BookResult r = luaService.rollback(fresh.getUserId(), fresh.getSlotId(),
            fresh.getServiceStaffId(), startTs, endTs, fresh.getId(), IdGenerator.nextId(), 1);

        if (r.success()) {
            log.info("Hold 超时回补成功 orderId={} logType={}", fresh.getId(), LogType.RESTORE.getCode());
        } else {
            throw new IllegalStateException("回补失败 code=" + r.getCode());
        }
    }
}
