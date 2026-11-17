package org.nearslot.appointment.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.TechRankService;
import org.nearslot.common.api.TechRankQuery;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 榜单只读查询实现（写榜在 PaymentService 支付流程，此处不写）。 */
@Service
@RequiredArgsConstructor
public class TechRankQueryImpl implements TechRankQuery {

    private final TechRankService techRankService;

    @Override
    public List<RankEntry> top(Long shopId, LocalDate date, int limit) {
        LocalDate d = date == null ? LocalDate.now() : date;
        List<Map<String, Object>> rows = techRankService.top(shopId, d, limit);
        return rows.stream().map(m -> new RankEntry(
            m.get("rank") == null ? 0 : ((Number) m.get("rank")).intValue(),
            m.get("serviceStaffId") == null ? null : Long.valueOf(String.valueOf(m.get("serviceStaffId"))),
            m.get("deals") == null ? 0 : ((Number) m.get("deals")).intValue()
        )).toList();
    }
}
