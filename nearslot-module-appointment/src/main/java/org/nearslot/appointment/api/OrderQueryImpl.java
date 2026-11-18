package org.nearslot.appointment.api;

import lombok.RequiredArgsConstructor;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.service.AppointmentService;
import org.nearslot.common.api.OrderQuery;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 订单查询实现。
 *
 * 归属校验：byId 传入 userId，不匹配返回 null（不抛越权异常，避免被用于探测订单是否存在）。
 */
@Service
@RequiredArgsConstructor
public class OrderQueryImpl implements OrderQuery {

    private final AppointmentService appointmentService;
    private final AppointmentOrderMapper orderMapper;

    @Override
    public OrderView byId(Long userId, Long orderId) {
        // 直接查 mapper：queryOrder 现返回 AppointmentView（前端展示视图），
        // 而 OrderQuery 契约消费方（AI 助手）需要实体字段——两者用途不同，不走同一路径。
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null || !userId.equals(o.getUserId())) {
            return null;   // 归属不符/不存在：统一返回 null，不泄露存在性
        }
        return toView(o);
    }

    @Override
    public List<OrderView> listByUser(Long userId, int limit) {
        return orderMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AppointmentOrder>lambdaQuery()
                    .eq(AppointmentOrder::getUserId, userId)
                    .orderByDesc(AppointmentOrder::getId)
                    .last("LIMIT " + Math.max(1, Math.min(limit, 50))))
            .stream().map(this::toView).toList();
    }

    private OrderView toView(AppointmentOrder o) {
        return new OrderView(o.getId(), o.getSlotId(), o.getShopId(), o.getServiceStaffId(),
            o.getStatus() == null ? 0 : o.getStatus(),
            statusText(o.getStatus()), o.getAmountCent(), null, o.getFulfillmentMode());
    }

    private String statusText(Integer s) {
        if (s == null) return "未知";
        return switch (s) {
            case 1 -> "待支付";
            case 2 -> "已确认";
            case 3 -> "已取消";
            case 4 -> "已完成";
            case 5 -> "已退款";
            default -> "未知";
        };
    }
}
