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
import org.nearslot.framework.delayqueue.RedissonDelayQueue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Hold 超时回收（FR-3.3 / research/08 §4）。
 *
 * 为何必须有：抢约成功后进入 Hold（待支付）状态，若用户不支付，
 * 余量与技师时间轴会被永久占用 → 时段假满。此任务负责超时回补。
 *
 * 实现取舍：用「定时扫描 + 状态机」而非仅依赖延迟队列，
 * 因为 Redisson 延迟队列消费失败会丢消息；扫描是幂等的兜底（research/09b §3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldTimeoutTask {

    /** 每 60s 扫描一次过期 Hold（低频，避免与业务争抢 DB）。 */
    private static final long SCAN_INTERVAL_MS = 60_000L;

    private final AppointmentOrderMapper orderMapper;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentLuaService luaService;
    private final RedissonDelayQueue delayQueue;

    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    public void scanExpiredHolds() {
        List<AppointmentOrder> expired = orderMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<AppointmentOrder>lambdaQuery()
                .eq(AppointmentOrder::getStatus, OrderStatus.PENDING.getCode())
                .lt(AppointmentOrder::getHoldExpiresAt, LocalDateTime.now()));
        if (expired.isEmpty()) return;
        log.info("发现过期 Hold {} 笔，开始回补", expired.size());
        expired.forEach(this::expireOne);
    }

    /** 单笔过期处理：状态置 CANCELLED + 回补 Redis（反转语义）。 */
    @Transactional(rollbackFor = Exception.class)
    public void expireOne(AppointmentOrder order) {
        try {
            TimeSlot slot = timeSlotMapper.selectById(order.getSlotId());
            if (slot == null) {
                log.warn("Hold 超时但时段不存在 orderId={}", order.getId());
                return;
            }
            order.setStatus(OrderStatus.CANCELLED.getCode());
            orderMapper.updateById(order);

            long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            BookResult r = luaService.rollback(order.getUserId(), order.getSlotId(),
                order.getTechnicianId(), startTs, endTs, order.getId(), nextTraceId(), 1);

            if (r.success()) {
                log.info("Hold 超时回补成功 orderId={} logType={}", order.getId(), LogType.RESTORE.getCode());
            } else {
                // 回补失败不得静默：抛出让事务回滚，下轮扫描重试
                log.error("Hold 超时回补失败 orderId={} code={}", order.getId(), r.getCode());
                throw new IllegalStateException("回补失败 code=" + r.getCode());
            }
        } catch (RuntimeException e) {
            log.error("Hold 超时处理异常 orderId={}", order.getId(), e);
            throw e;
        }
    }

    /** traceId 同样走成熟库雪花（ADR-016），避免手写自增。 */
    private long nextTraceId() { return org.nearslot.framework.id.IdGenerator.nextId(); }
}
