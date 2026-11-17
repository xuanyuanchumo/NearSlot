package org.nearslot.appointment.domain;

import lombok.Data;

/** Lua 抢约结果（单一码表 C-CON-01，杜绝 0/1/2 双口径）。 */
@Data
public class BookResult {
    private int code;
    private int beforeQty;
    private int deductQty;
    private int afterQty;

    public boolean success() { return code == 200; }
}
