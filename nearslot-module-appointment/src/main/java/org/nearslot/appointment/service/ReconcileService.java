package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.ReconcileLog;
import org.nearslot.appointment.mapper.ReconcileLogMapper;
import org.nearslot.common.enums.LogType;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.stereotype.Service;

/**
 * 对账日志写入（best-effort：失败不阻断主流程，research/07 §3）。
 *
 * 归属 appointment 域（与订单同分片键，便于库内 JOIN）。
 * 写入失败仅告警：对账日志是**观测手段**，不能反向影响业务事务成败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileService {

    public static final int RESULT_SUCCESS = 1;
    public static final int RESULT_FAIL = 2;

    private final ReconcileLogMapper logMapper;

    /** 写入对账日志，异常仅记 warn。 */
    public void writeBestEffort(Long orderId, LogType type, boolean success,
                                int beforeQty, int changeQty, int afterQty, Long traceId, String remark) {
        try {
            ReconcileLog l = new ReconcileLog();
            l.setId(IdGenerator.nextId());
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
}
