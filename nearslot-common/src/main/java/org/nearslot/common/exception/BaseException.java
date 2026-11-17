package org.nearslot.common.exception;

import lombok.Getter;
import org.nearslot.common.enums.BaseCode;

/** 业务异常基类（coding.md §2：异常不带栈信息进 message）。 */
@Getter
public class BaseException extends RuntimeException {
    private final BaseCode code;
    public BaseException(BaseCode code) { super(code.getMessage()); this.code = code; }
    public BaseException(BaseCode code, String message) { super(message); this.code = code; }
}
