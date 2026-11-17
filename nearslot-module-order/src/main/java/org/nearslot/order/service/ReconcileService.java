package org.nearslot.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.LogType;
import org.nearslot.order.entity.ReconcileLog;
import org.nearslot.order.mapper.ReconcileLogMapper;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对账日志写入（best-effort：失败不阻断主流程，research/07 §3）。
 * 在途保护窗口：traceTs 距今 < 延迟阈值+2s 视为消息仍在途，不判异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {
    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_FAIL = 2;
    /** 在途保护窗口（research/09b §2：延迟阈值 10s + 2s）。 */
    public static final long IN_FLIGHT_WINDOW_MS = 12_000L;

    private final ReconcileLogMapper logMapper;

    /** 写入对账日志，异常仅记 warn（不反向影响业务事务）。 */
    public void writeBestEffort(Long orderId, LogType type, boolean success,
                                int beforeQty, int changeQty, int afterQty, Long traceId, String remark) {
        try {
            ReconcileLog l = new ReconcileLog();
            l.setId(org.nearslot.framework.id.IdGenerator.nextId());
            l.setOrderId(orderId);
            l.setLogType(type.getCode());
            l.setResult(success ? RESULT_SUCCESS : RESULT_FAIL);
            l.setBeforeQty(beforeQty);
            l.setChangeQty(changeQty);
            l.setAfterQty(afterQty);
            l.setTraceId(traceId);
            l.setRemark(remark == null ? "" : remark);
            logMapper.insert(l);
        } catch (RuntimeException e) {
            log.warn("对账日志写入失败（best-effort 不阻断）orderId={} err={}", orderId, e.toString());
        }
    }

    /** 在途保护：真实时间差不超过窗口则跳过对账判定。 */
    public boolean stillInFlight(long traceTs, long now) {
        return now - traceTs < IN_FLIGHT_WINDOW_MS;
    }
}
