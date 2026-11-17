package org.nearslot.common;

import lombok.Data;
import org.nearslot.common.enums.BaseCode;

/** 统一响应体（api/design.md §2）；data 中 Long 由 jackson WRITE_NUMBERS_AS_STRINGS 串化。 */
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    private long timestamp;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(BaseCode.SUCCESS.getCode()); r.setMessage(BaseCode.SUCCESS.getMessage());
        r.setData(data); r.setTimestamp(System.currentTimeMillis()); return r;
    }
    public static <T> Result<T> fail(BaseCode c) {
        Result<T> r = new Result<>();
        r.setCode(c.getCode()); r.setMessage(c.getMessage()); r.setTimestamp(System.currentTimeMillis()); return r;
    }
    public static <T> Result<T> fail(BaseCode c, String message) {
        Result<T> r = fail(c); r.setMessage(message); return r;
    }
}
