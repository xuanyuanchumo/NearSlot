package org.nearslot.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 预约单状态机（FR-4.1）。 */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING(1, "待支付"), CONFIRMED(2, "已确认"), CANCELLED(3, "已取消"),
    DONE(4, "已完成"), REFUNDED(5, "已退款");
    private final int code;
    private final String desc;
}
