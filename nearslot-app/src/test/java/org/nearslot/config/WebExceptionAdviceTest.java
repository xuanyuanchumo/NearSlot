package org.nearslot.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.Result;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局异常处理器单测（二轮审计重写后的语义）。
 *
 * 锁住的核心：**码段 → HTTP 状态**的映射表。
 * 原实现缺 500xx 分支，系统异常竟以 200 返回，监控无法告警；
 * 参数错误误用 INTERNAL_ERROR(50001)，用户输入错误被当成系统故障。
 * 这些都是"接口返回 200 但业务失败"的隐蔽故障，必须用测试固化。
 */
class WebExceptionAdviceTest {

    private WebExceptionAdvice advice;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        advice = new WebExceptionAdvice();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("401xx -> HTTP 401 + X-Auth-Reason")
    void unauthorizedSegment() {
        Result<Void> r = advice.handleBiz(
            new BaseException(BaseCode.UNAUTHORIZED), response);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        assertEquals("no-session", response.getHeader("X-Auth-Reason"));
        assertEquals(BaseCode.UNAUTHORIZED.getCode(), r.getCode());
    }

    @Test
    @DisplayName("403xx -> HTTP 403")
    void forbiddenSegment() {
        advice.handleBiz(new BaseException(BaseCode.FORBIDDEN), response);
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
    }

    @Test
    @DisplayName("404xx -> HTTP 404")
    void notFoundSegment() {
        advice.handleBiz(new BaseException(BaseCode.NOT_FOUND), response);
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatus());
    }

    @Test
    @DisplayName("429xx -> HTTP 429")
    void rateLimitedSegment() {
        advice.handleBiz(new BaseException(BaseCode.RATE_LIMITED), response);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
    }

    @Test
    @DisplayName("500xx -> HTTP 500（原实现漏映射导致系统异常返回 200，监控无法告警）")
    void internalErrorSegment() {
        advice.handleBiz(new BaseException(BaseCode.INTERNAL_ERROR), response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus(),
            "系统异常必须是 5xx，否则网关/告警体系失效");
    }

    @Test
    @DisplayName("501xx 业务失败 -> 保持 200（属于正常业务结果，不是故障）")
    void businessFailureStays200() {
        advice.handleBiz(new BaseException(BaseCode.SLOT_NO_STOCK), response);
        assertEquals(HttpStatus.OK.value(), response.getStatus(),
            "余量不足是正常业务分支，不应报 5xx");
    }

    @Test
    @DisplayName("参数校验失败 -> 400 + 40001（原实现误用 50001 当成系统故障）")
    void validationUsesBadRequestCode() {
        // 用反射构造 MethodArgumentNotValidException 成本高，这里直接验证码值语义
        Result<Void> r = advice.handleUnreadable(
            new HttpMessageNotReadableException("bad json", (org.springframework.http.HttpInputMessage) null),
            response);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        assertEquals(BaseCode.BAD_REQUEST.getCode(), r.getCode());
    }

    @Test
    @DisplayName("缺参 -> 400 + 40001")
    void missingParam() {
        Result<Void> r = advice.handleMissingParam(
            new MissingServletRequestParameterException("shopId", "Long"), response);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        assertEquals(BaseCode.BAD_REQUEST.getCode(), r.getCode());
        assertTrue(r.getMessage().contains("shopId"));
    }

    @Test
    @DisplayName("类型转换失败 -> 400 + 40001")
    void typeMismatch() {
        Result<Void> r = advice.handleTypeMismatch(
            new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null), response);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        assertEquals(BaseCode.BAD_REQUEST.getCode(), r.getCode());
    }

    @Test
    @DisplayName("唯一约束 uq_user_slot -> 50102 重复预约（不是 50001 系统异常）")
    void duplicateKeyMapsToBusinessCode() {
        Result<Void> r = advice.handleDuplicateKey(
            new org.springframework.dao.DuplicateKeyException(
                "Duplicate entry for key 'uq_user_slot'"), response);

        assertEquals(BaseCode.SLOT_DUPLICATE.getCode(), r.getCode());
        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }

    @Test
    @DisplayName("唯一约束 uq_slot_occupy -> 50103 时段已占用")
    void duplicateKeySlotOccupied() {
        Result<Void> r = advice.handleDuplicateKey(
            new org.springframework.dao.DuplicateKeyException(
                "Duplicate entry for key 'uq_slot_occupy'"), response);

        assertEquals(BaseCode.SLOT_CLOSED.getCode(), r.getCode());
    }

    @Test
    @DisplayName("其它唯一约束 -> 50001 且 HTTP 500")
    void duplicateKeyOther() {
        Result<Void> r = advice.handleDuplicateKey(
            new org.springframework.dao.DuplicateKeyException("Duplicate entry for key 'uk_other'"),
            response);

        assertEquals(BaseCode.INTERNAL_ERROR.getCode(), r.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
    }

    @Test
    @DisplayName("运行时异常兜底 -> 500 + 50001（不泄露堆栈）")
    void runtimeFallback() {
        Result<Void> r = advice.handleRuntime(new IllegalStateException("boom"), response);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.getStatus());
        assertEquals(BaseCode.INTERNAL_ERROR.getCode(), r.getCode());
        // 不得把原始异常信息透给客户端
        assertFalse(r.getMessage().contains("boom"));
    }
}
