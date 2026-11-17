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
 * 全局异常处理器（命名对齐 PickNear / hmdp-plus 的 WebExceptionAdvice，
 * 降低接手者认知成本，见 standards/library-first.md §3 第 4 项）。
 *
 * 实测背景：未捕获的 BaseException 会被 Spring /error 渲染成 HTTP 500，
 * 与契约「业务失败返回统一 Result」不符，前端无法按码分流。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** 业务异常：按码段映射 HTTP 状态，body 恒为统一响应体。 */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBiz(BaseException e, HttpServletResponse response) {
        BaseCode code = e.getCode();
        int segment = code.getCode() / 100;
        if (segment == 401) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader("X-Auth-Reason", "no-session");
        } else if (segment == 403) {
            // 权限不足（如内部接口令牌校验失败）：必须返回真实 403，
            // 否则监控/网关无法按状态码告警（实测踩坑：曾误返 200）
            response.setStatus(HttpStatus.FORBIDDEN.value());
        } else if (segment == 404) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
        } else if (segment == 429) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
        log.warn("业务异常 code={} msg={}", code.getCode(), e.getMessage());
        return Result.fail(code, e.getMessage());
    }

    /** 参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("参数校验失败");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(BaseCode.INTERNAL_ERROR, msg);
    }

    /** 运行时异常兜底。 */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntime(RuntimeException e, HttpServletResponse response) {
        log.error("未捕获运行时异常", e);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return Result.fail(BaseCode.INTERNAL_ERROR);
    }
}
