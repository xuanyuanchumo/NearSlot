package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.appointment.entity.ReconcileLog;
import org.nearslot.appointment.mapper.ReconcileLogMapper;
import org.nearslot.common.enums.LogType;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 对账日志写入单测（best-effort 语义：失败不阻断业务）。 */
class ReconcileServiceTest {

    private ReconcileLogMapper logMapper;
    private ReconcileService service;

    @BeforeEach
    void setUp() {
        logMapper = mock(ReconcileLogMapper.class);
        service = new ReconcileService(logMapper);
    }

    @Test
    @DisplayName("成功日志：result=1 且字段完整落库")
    void writesSuccessLog() {
        service.writeBestEffort(100L, LogType.BOOK, true, 5, -1, 4, 999L, "ok");

        verify(logMapper).insert(argThat((ReconcileLog l) ->
            l.getOrderId().equals(100L)
                && l.getResult() != null && l.getResult() == ReconcileService.RESULT_SUCCESS
                && l.getBeforeQty() == 5 && l.getChangeQty() == -1 && l.getAfterQty() == 4
                && l.getTraceId().equals(999L)
                && "ok".equals(l.getRemark())));
    }

    @Test
    @DisplayName("失败日志：result=2")
    void writesFailLog() {
        service.writeBestEffort(101L, LogType.RESTORE, false, 4, 1, 5, 1000L, "rollback");
        verify(logMapper).insert(argThat((ReconcileLog l) ->
            l.getResult() != null && l.getResult() == ReconcileService.RESULT_FAIL));
    }

    @Test
    @DisplayName("remark 为 null -> 存空串（DB 列 NOT NULL）")
    void nullRemarkBecomesEmpty() {
        service.writeBestEffort(102L, LogType.BOOK, true, 1, -1, 0, 1L, null);
        verify(logMapper).insert(argThat((ReconcileLog l) -> "".equals(l.getRemark())));
    }

    @Test
    @DisplayName("best-effort：DB 异常被吞掉，不向上抛（不能反向影响业务事务）")
    void swallowsDbException() {
        when(logMapper.insert(any(ReconcileLog.class))).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() ->
            service.writeBestEffort(103L, LogType.BOOK, true, 1, -1, 0, 1L, "x"));
    }

    @Test
    @DisplayName("每次写入生成独立雪花 ID")
    void generatesUniqueId() {
        service.writeBestEffort(104L, LogType.BOOK, true, 1, -1, 0, 1L, "a");
        service.writeBestEffort(105L, LogType.BOOK, true, 1, -1, 0, 1L, "b");
        verify(logMapper, times(2)).insert(any(ReconcileLog.class));
    }
}
