package org.nearslot.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.Result;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器（命名对齐 PickNear / hmdp-plus 的 WebExceptionAdvice，
 * 降低接手者认知成本，见 standards/library-first.md §3 第 4 项）。
 *
 * 实测背景：未捕获的 BaseException 会被 Spring /error 渲染成 HTTP 500，
 * 与契约「业务失败返回统一 Result」不符，前端无法按码分流。
 *
 * **2026-11-17 审计修复（Major×4）**：
 * ① 缺少 500xx 段映射 —— 系统异常曾以 HTTP 200 返回，监控/网关无法告警；
 * ② 只兜 RuntimeException —— 请求体解析失败、缺参、类型转换错误全落 500；
 * ③ 参数校验失败误用 INTERNAL_ERROR(50001) —— 用户输入错误被当成系统故障；
 * ④ DuplicateKey 显式 setStatus(200) —— 与 ① 自相矛盾。
 * 现统一为**表驱动**的码段 → HTTP 状态映射，且新增 40001 参数错误码。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** 业务异常：按码段映射 HTTP 状态，body 恒为统一响应体。 */
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBiz(BaseException e, HttpServletResponse response) {
        BaseCode code = e.getCode();
        applySegmentStatus(code.getCode(), response);
        log.warn("业务异常 code={} msg={}", code.getCode(), e.getMessage());
        return Result.fail(code, e.getMessage());
    }

    /**
     * 码段 → HTTP 状态的唯一映射表（禁止在各分支里散落 status 赋值）。
     * 段口径与 BaseCode 注释一致：401xx 认证 / 403xx 权限 / 404xx 资源 /
     * 429xx 限流幂等 / 500xx 系统 / 501xx 业务 / 502xx AI。
     */
    private void applySegmentStatus(int code, HttpServletResponse response) {
        int segment = code / 100;
        switch (segment) {
            case 401 -> {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                // 精确原因由 controller/interceptor 决定；此处只给兜底值
                if (response.getHeader("X-Auth-Reason") == null) {
                    response.setHeader("X-Auth-Reason", "no-session");
                }
            }
            case 403 -> response.setStatus(HttpStatus.FORBIDDEN.value());
            case 404 -> response.setStatus(HttpStatus.NOT_FOUND.value());
            case 429 -> response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            // 500xx 系统异常必须 5xx：监控、告警、网关重试策略都依赖真实状态码
            case 500 -> response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            // 501xx 业务失败（余量/重复/等级）保持 200：属于正常业务结果，不是故障
            // 502xx AI 助手段同上
            default -> response.setStatus(HttpStatus.OK.value());
        }
    }

    /** 参数校验失败（@Valid）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e, HttpServletResponse response) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("参数校验失败");
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(BaseCode.BAD_REQUEST, msg);
    }

    /** 请求体不可读（JSON 语法错误 / 类型不匹配）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(BaseCode.BAD_REQUEST, "请求体格式错误");
    }

    /** 缺少必填查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e,
                                           HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(BaseCode.BAD_REQUEST, "缺少参数: " + e.getParameterName());
    }

    /** 参数类型转换失败（如 id 传了非数字）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                           HttpServletResponse response) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return Result.fail(BaseCode.BAD_REQUEST, "参数类型错误: " + e.getName());
    }

    /** 404 路由未命中（若启用 throw-exception-if-no-handler-found）。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandler(NoHandlerFoundException e, HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return Result.fail(BaseCode.NOT_FOUND);
    }

    /**
     * 数据库唯一约束冲突 -> 业务语义码。
     *
     * 实测缺陷：并发/重复请求时 DB 层唯一约束（uq_user_slot 防一人一时段）
     * 会抛 DuplicateKeyException，若落到 RuntimeException 兜底就返回
     * 50001「系统异常」——语义完全错误，用户以为是服务故障，
     * 实际是系统**正确拒绝**了重复预约。
     *
     * 映射规则：约束名含 uq_user_slot -> 50102 重复预约；
     *          含 uq_slot_occupy  -> 50103 时段已占用；其余保持系统异常(500)。
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(org.springframework.dao.DuplicateKeyException e,
                                           HttpServletResponse response) {
        String msg = String.valueOf(e.getMessage());
        BaseCode code;
        if (msg.contains("uq_user_slot")) {
            code = BaseCode.SLOT_DUPLICATE;
        } else if (msg.contains("uq_slot_occupy")) {
            code = BaseCode.SLOT_CLOSED;
        } else {
            code = BaseCode.INTERNAL_ERROR;
        }
        // 与业务码同口径：只有真·系统异常才 5xx
        applySegmentStatus(code.getCode(), response);
        log.warn("唯一约束冲突已转为业务码 code={} constraint={}",
            code.getCode(), msg.contains("uq_user_slot") ? "uq_user_slot" : "other");
        return Result.fail(code);
    }

    /** 运行时异常兜底：必须 500，且不把堆栈细节透给客户端。 */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntime(RuntimeException e, HttpServletResponse response) {
        log.error("未捕获运行时异常", e);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return Result.fail(BaseCode.INTERNAL_ERROR);
    }
}
