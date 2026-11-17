package org.nearslot.appointment.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.service.SlotPublishService;
import org.nearslot.common.api.SlotAvailabilityQuery;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 时段可约性查询实现（薄封装 SlotPublishService.listAvailable，不重复业务逻辑）。 */
@Service
@RequiredArgsConstructor
public class SlotAvailabilityQueryImpl implements SlotAvailabilityQuery {

    private final SlotPublishService slotPublishService;

    @Override
    public List<SlotView> available(Long shopId, LocalDate date, Long serviceItemId) {
        String d = date == null ? null : date.toString();
        List<Map<String, Object>> raw = slotPublishService.listAvailable(shopId, d, serviceItemId);
        return raw.stream().map(m -> new SlotView(
            toLong(m.get("id")),
            String.valueOf(m.get("startsAt")),
            String.valueOf(m.get("endsAt")),
            m.get("remaining") == null ? null : ((Number) m.get("remaining")).intValue(),
            toLong(m.get("serviceStaffId")),
            m.get("minLevel") == null ? null : ((Number) m.get("minLevel")).intValue()
        )).toList();
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }
}
