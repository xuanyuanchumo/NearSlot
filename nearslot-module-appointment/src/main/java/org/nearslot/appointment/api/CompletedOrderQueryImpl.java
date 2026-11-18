package org.nearslot.appointment.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.CompletedOrderQuery;
import org.nearslot.common.enums.OrderStatus;
import org.springframework.stereotype.Service;

/**
 * 履约订单校验实现（FR-7 / FR-12 / ADR-026）。
 *
 * 内容模块发布笔记、提交评价时调用：只有**已完成**订单才能产出内容。
 * 这是 NearSlot 相对两个参考项目的差异化能力 —— 它们允许无订单发帖评星，
 * 内容与真实消费脱钩，无法防刷单水军。
 *
 * 评价还需要 serviceItemId，订单表本身不存该字段（它是时段属性），
 * 因此通过 slotId 回查 time_slot；查不到就返回 null（宁可拒绝评价，也不给脏数据）。
 */
@Service
@RequiredArgsConstructor
public class CompletedOrderQueryImpl implements CompletedOrderQuery {

    private final AppointmentOrderMapper orderMapper;
    private final TimeSlotMapper timeSlotMapper;

    @Override
    public boolean isCompletedBy(Long userId, Long orderId) {
        return reviewableOf(userId, orderId) != null;
    }

    @Override
    public Long shopIdOf(Long orderId) {
        if (orderId == null) return null;
        AppointmentOrder o = orderMapper.selectById(orderId);
        return o == null ? null : o.getShopId();
    }

    @Override
    public ReviewableOrder reviewableOf(Long userId, Long orderId) {
        if (userId == null || orderId == null) return null;
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null || !userId.equals(o.getUserId())) return null;
        if (o.getStatus() == null || o.getStatus() != OrderStatus.DONE.getCode()) return null;
        Long serviceItemId = null;
        if (o.getSlotId() != null) {
            TimeSlot slot = timeSlotMapper.selectById(o.getSlotId());
            serviceItemId = slot == null ? null : slot.getServiceItemId();
        }
        // 完成时间用订单更新时间近似：核销会把订单推进到 DONE，updated_at 即核销时刻
        return new ReviewableOrder(o.getId(), o.getShopId(), o.getServiceStaffId(),
            serviceItemId, o.getUpdatedAt());
    }
}
