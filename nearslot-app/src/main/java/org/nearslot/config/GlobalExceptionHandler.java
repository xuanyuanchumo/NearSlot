package org.nearslot.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.Result;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（api/design.md §2/§3）。
 *
 * 为何必须有：本轮实测发现，未捕获的 BaseException 会被 Spring 默认的
 * /error 兜底渲染成 HTTP 500 + {"status":500}，与契约「业务失败返回 200 + code」
 * 严重不符，前端无法按码分流。此处统一转成 Result。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按码段决定 HTTP 状态，body 始终为统一响应体。 */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBase(BaseException e, HttpServletResponse response) {
        BaseCode code = e.getCode();
        // 认证/限流类走对应 HTTP 状态；业务类保持 200 由前端按 code 分流
        if (code.getCode() / 100 == 401) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader("X-Auth-Reason", "no-session");
        } else if (code.getCode() / 100 == 429) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
        log.warn("业务异常 code={} msg={}", code.getCode(), e.getMessage());
        return Result.fail(code, e.getMessage());
    }

    /** 参数校验失败 → 501 段前的通用 400 语义，仍返回统一体。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("参数校验失败");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(BaseCode.INTERNAL_ERROR, msg);
    }

    /** 兜底：未知异常记日志并返回系统错误码。 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e, HttpServletResponse response) {
        log.error("未捕获异常", e);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return Result.fail(BaseCode.INTERNAL_ERROR);
    }
}
