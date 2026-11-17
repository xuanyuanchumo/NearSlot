package org.nearslot.common.api;

import java.util.List;

/** 订单查询（跨模块契约）。agent 的 queryOrder / queryMyAppointments 真值来源。 */
public interface OrderQuery {

    /** 按 ID 查单；必须校验归属（userId 不匹配返回 null，不抛越权异常以免探活）。 */
    OrderView byId(Long userId, Long orderId);

    /** 我的预约列表。 */
    List<OrderView> listByUser(Long userId, int limit);

    /** 订单视图：状态用码+文案双给，便于 agent 直接翻译。 */
    record OrderView(Long orderId, Long slotId, Long shopId, Long serviceStaffId,
                     int status, String statusText, Integer amountCent,
                     String startsAt, String fulfillmentMode) {}
}
