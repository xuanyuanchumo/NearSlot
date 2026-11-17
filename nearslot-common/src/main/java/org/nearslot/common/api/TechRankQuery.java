package org.nearslot.common.api;

import java.time.LocalDate;
import java.util.List;

/** 热门服务者榜查询（跨模块契约）。只读，不写榜（写榜在 provider 侧支付流程）。 */
public interface TechRankQuery {

    /** 某店铺某日 Top N。 */
    List<RankEntry> top(Long shopId, LocalDate date, int limit);

    record RankEntry(int rank, Long serviceStaffId, int deals) {}
}
