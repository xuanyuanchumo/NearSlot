package org.nearslot.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** trace 日志类型（与 Lua 共享：BOOK/RESTORE）。 */
@Getter
@AllArgsConstructor
public enum LogType {
    BOOK(1, "预扣"), RESTORE(2, "回补");
    private final int code;
    private final String desc;
}
