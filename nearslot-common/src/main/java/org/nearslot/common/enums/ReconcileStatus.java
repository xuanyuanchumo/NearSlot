package org.nearslot.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 对账状态（对齐 hmdp-plus ReconciliationStatus）。
 * 注意：hmdp-plus 的 INCONSISTENT(3) 实测为死码从未使用，此处不引入，避免照抄死代码。
 */
@Getter
@AllArgsConstructor
public enum ReconcileStatus {
    PENDING(1, "待对账"),
    ABNORMAL(2, "不一致"),
    CONSISTENT(4, "已一致");
    private final int code;
    private final String desc;
}
