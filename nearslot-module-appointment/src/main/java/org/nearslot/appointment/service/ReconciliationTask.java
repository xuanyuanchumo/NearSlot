package org.nearslot.appointment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.ReconcileLog;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.ReconcileLogMapper;
import org.nearslot.common.enums.ReconcileStatus;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 对账定时任务（Phase 2 出口标准：对账 ABNORMAL=0）。
 *
 * **模块归属说明**：本任务放在 appointment 模块而非 order 模块，
 * 因为订单实体 `AppointmentOrder` 归属 appointment；若放 order 会造成
 * module 之间互相依赖，违反依赖铁律 C-ARCH-01（ArchUnit 已强制）。
 *
 * **三方互证**（对齐 hmdp-plus ReconciliationTaskServiceImpl 语义）：
 *   1. Redis trace 审计日志 —— Lua 扣减时原子写入的「扣减事实」；
 *   2. DB 对账日志 —— 消费端写入的 BOOK/RESTORE 记录；
 *   3. DB 订单 —— 最终业务状态。
 * 三者自洽才标 CONSISTENT，否则标 ABNORMAL 并告警。
 *
 * **在途保护窗口**（research/09b §2 关键纠错）：
 * traceTs 距今 < 12s（延迟阈值 10s + 2s）视为消息在途，**跳过判定**，
 * 否则会把「消息还没到」误判成「数据不一致」，产生大量假告警。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationTask {

    private static final long RECONCILE_INTERVAL_MS = 5 * 60_000L;

    /** 在途保护窗口：延迟阈值 10s + 2s（research/09b §2）。 */
    public static final long IN_FLIGHT_WINDOW_MS = 12_000L;

    private final AppointmentOrderMapper orderMapper;
    private final ReconcileLogMapper logMapper;
    private final StringRedisTemplate redis;
    /** 对账批大小外置到 `nearslot.appointment.reconcile-batch-size`。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;
    /** 业务指标出口（可选注入，便于单测不依赖 Micrometer）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS)
    public void reconcile() {
        // 用 MP Page 代替手拼 LIMIT；批大小外置到 `nearslot.appointment.reconcile-batch-size`
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<AppointmentOrder>(
            1, props.getReconcileBatchSize());
        List<AppointmentOrder> pending = orderMapper.selectPage(page,
            Wrappers.<AppointmentOrder>lambdaQuery()
                .and(w -> w.isNull(AppointmentOrder::getReconcileStatus)
                           .or().eq(AppointmentOrder::getReconcileStatus,
                                    ReconcileStatus.PENDING.getCode()))
                .orderByAsc(AppointmentOrder::getId)).getRecords();
        if (pending.isEmpty()) return;

        int consistent = 0, abnormal = 0, inFlight = 0;
        for (AppointmentOrder order : pending) {
            switch (checkOne(order)) {
                case CONSISTENT -> consistent++;
                case ABNORMAL -> abnormal++;
                case IN_FLIGHT -> inFlight++;
            }
        }
        log.info("对账完成：一致={} 异常={} 在途跳过={} 总计={}",
            consistent, abnormal, inFlight, pending.size());

        // 业务指标（G5 监控要求）：对账结果是 SLO 硬指标，必须可被 Prometheus 抓取
        if (meterRegistry != null) {
            meterRegistry.counter("nearslot.reconcile.orders",
                "verdict", "consistent").increment(consistent);
            meterRegistry.counter("nearslot.reconcile.orders",
                "verdict", "in_flight").increment(inFlight);
            if (abnormal > 0) {
                meterRegistry.counter("nearslot.reconcile.abnormal").increment(abnormal);
            }
        }

        if (abnormal > 0) {
            log.error("对账发现 {} 笔不一致，需立即排查（SLO：ABNORMAL 必须为 0）", abnormal);
        }
    }

    /** 单笔三方互证。 */
    Verdict checkOne(AppointmentOrder order) {
        List<ReconcileLog> logs = logMapper.selectList(
            Wrappers.<ReconcileLog>lambdaQuery().eq(ReconcileLog::getOrderId, order.getId()));

        if (logs.isEmpty()) {
            Map<Object, Object> traces = loadTraceLogs(order.getSlotId());
            if (traces.isEmpty()) {
                markStatus(order.getId(), ReconcileStatus.ABNORMAL);
                return Verdict.ABNORMAL;
            }
            long traceTs = extractTraceTs(traces);
            if (System.currentTimeMillis() - traceTs < IN_FLIGHT_WINDOW_MS) {
                return Verdict.IN_FLIGHT;
            }
            markStatus(order.getId(), ReconcileStatus.ABNORMAL);
            return Verdict.ABNORMAL;
        }

        if (logs.size() <= 2) {
            boolean allSuccess = logs.stream().allMatch(l -> l.getResult() != null && l.getResult() == 1);
            if (!allSuccess) {
                markStatus(order.getId(), ReconcileStatus.ABNORMAL);
                return Verdict.ABNORMAL;
            }
            markStatus(order.getId(), ReconcileStatus.CONSISTENT);
            return Verdict.CONSISTENT;
        }

        markStatus(order.getId(), ReconcileStatus.ABNORMAL);
        return Verdict.ABNORMAL;
    }

    /** 读取 Redis trace（Lua 扣减时写入的审计日志）。 */
    Map<Object, Object> loadTraceLogs(long slotId) {
        try {
            return redis.opsForHash().entries(RedisConstants.SLOT_TRACE + slotId);
        } catch (RuntimeException e) {
            log.warn("读取 trace 日志失败 slotId={}", slotId, e);
            return Map.of();
        }
    }

    /** 从 trace 条目 JSON 提取 ts（秒）转毫秒。 */
    private long extractTraceTs(Map<Object, Object> traces) {
        try {
            String json = String.valueOf(traces.values().iterator().next());
            int idx = json.indexOf("\"ts\":");
            if (idx < 0) return 0L;
            int start = idx + 5, end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return Long.parseLong(json.substring(start, end)) * 1000;
        } catch (RuntimeException e) {
            log.warn("trace 时间戳解析失败，按已超时处理", e);
            return 0L;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    void markStatus(Long orderId, ReconcileStatus status) {
        AppointmentOrder upd = new AppointmentOrder();
        upd.setId(orderId);
        upd.setReconcileStatus(status.getCode());
        orderMapper.updateById(upd);
    }

    /** 对账判定。 */
    enum Verdict { CONSISTENT, ABNORMAL, IN_FLIGHT }
}
