package org.nearslot.common.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 时段可约性查询（跨模块契约，遵守 C-ARCH-01）。
 *
 * 为何放 common：agent 需要时段真值，但不能依赖 shop/appointment 实现类。
 * 返回 DTO 只含 prompt 需要的字段，禁止回传完整实体（防全实体外泄到 prompt）。
 */
public interface SlotAvailabilityQuery {

    /** 查询某店铺某日可约时段；无数据返回空集合，禁止返回 null。 */
    List<SlotView> available(Long shopId, LocalDate date, Long serviceItemId);

    /** 时段视图：仅暴露 prompt 需要的字段（ADR-021 起服务者字段为 serviceStaffId）。 */
    record SlotView(Long slotId, String startTime, String endTime,
                    Integer remain, Long serviceStaffId, Integer minLevel) {}
}
