package org.nearslot.framework.idempotent;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 幂等切面单测（ADR-016：RBucket.trySet 原子占位）。
 * 重点覆盖：重复请求拦截、业务异常释放幂等键、参数名缺失时的 arg0 兜底、
 * 非业务异常不释放键（保证占位语义不被普通故障打破）。
 */
class IdempotentAspectTest {

    private RedissonClient redisson;
    private RBucket<Object> bucket;
    private IdempotentAspect aspect;

    /** 真实方法容器：MethodSignature.getMethod() 必须返回带 @Idempotent 的方法。 */
    static class DemoTarget {
        @Idempotent(key = "#orderId", ttl = 30)
        public String byOrderId(String orderId) {
            return "done";
        }

        @Idempotent(key = "'fixed:' + #arg0")
        public String byFixed(String id) {
            return "ok";
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(bucket);
        aspect = new IdempotentAspect(redisson);
        clearInvocations(redisson, bucket);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ProceedingJoinPoint pjp(String method, String[] paramNames, Object[] args) throws Exception {
        Method target = DemoTarget.class.getMethod(method, String.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(target);
        when(sig.getParameterNames()).thenReturn(paramNames);
        when(joinPoint.getSignature()).thenReturn(sig);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    @Test
    @DisplayName("首次请求 -> 用 SpEL 拼出幂等键、按 ttl 占位，并执行原方法")
    void firstCallProceeds() throws Throwable {
        ProceedingJoinPoint pjp = pjp("byOrderId", new String[]{"orderId"}, new Object[]{"A1"});
        when(bucket.trySet(eq("1"), eq(30L), any())).thenReturn(true);
        when(pjp.proceed()).thenReturn("done");
        clearInvocations(bucket);

        Object result = aspect.around(pjp);

        assertEquals("done", result);
        verify(redisson).getBucket("nearslot:idem:A1");
        verify(bucket).trySet("1", 30L, java.util.concurrent.TimeUnit.SECONDS);
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("重复请求（键已存在）-> 抛 IDEMPOTENT_HIT，且绝不执行原方法")
    void duplicateRejected() throws Throwable {
        ProceedingJoinPoint pjp = pjp("byOrderId", new String[]{"orderId"}, new Object[]{"A1"});
        when(bucket.trySet(any(), anyLong(), any())).thenReturn(false);
        clearInvocations(bucket);

        BaseException e = assertThrows(BaseException.class, () -> aspect.around(pjp));

        assertEquals(BaseCode.IDEMPOTENT_HIT, e.getCode());
        assertEquals("重复提交", e.getMessage());
        verify(pjp, never()).proceed();
        verify(bucket, never()).delete();
    }

    @Test
    @DisplayName("业务异常 -> 删除幂等键（异常不占用键，可重试）并透传异常")
    void businessExceptionReleasesKey() throws Throwable {
        ProceedingJoinPoint pjp = pjp("byOrderId", new String[]{"orderId"}, new Object[]{"A1"});
        when(bucket.trySet(any(), anyLong(), any())).thenReturn(true);
        BaseException boom = new BaseException(BaseCode.SLOT_NO_STOCK);
        when(pjp.proceed()).thenThrow(boom);
        // 实现里 getBucket 被调用两次（trySet 一次、异常后 delete 一次），
        // 必须让两次都返回同一个 mock，否则第二次拿到 null 会 NPE。
        lenient().when(redisson.getBucket("nearslot:idem:A1")).thenReturn(bucket);
        clearInvocations(bucket);

        BaseException thrown = assertThrows(BaseException.class, () -> aspect.around(pjp));

        assertSame(boom, thrown);
        verify(bucket).delete();
    }

    @Test
    @DisplayName("非业务异常 -> 不删除幂等键、异常照常透传（占位语义不被普通故障打破）")
    void nonBusinessExceptionKeepsKey() throws Throwable {
        ProceedingJoinPoint pjp = pjp("byOrderId", new String[]{"orderId"}, new Object[]{"A1"});
        when(bucket.trySet(any(), anyLong(), any())).thenReturn(true);
        RuntimeException boom = new IllegalStateException("DB 挂了");
        when(pjp.proceed()).thenThrow(boom);
        clearInvocations(bucket);

        Throwable thrown = assertThrows(IllegalStateException.class, () -> aspect.around(pjp));

        assertSame(boom, thrown);
        verify(bucket, never()).delete();
    }

    @Test
    @DisplayName("编译未开 -parameters（参数名为 null）-> 用 arg0 兜底，不抛 NPE")
    void nullParameterNamesFallsBackToArg0() throws Throwable {
        ProceedingJoinPoint pjp = pjp("byFixed", null, new Object[]{"B7"});
        when(bucket.trySet(any(), anyLong(), any())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");
        clearInvocations(bucket);

        assertEquals("ok", aspect.around(pjp));

        verify(redisson).getBucket("nearslot:idem:fixed:B7");
    }

    @Test
    @DisplayName("ttl 使用注解声明值（未声明时默认 10 秒）")
    void ttlFromAnnotation() throws Throwable {
        ProceedingJoinPoint pjp = pjp("byFixed", new String[]{"id"}, new Object[]{"C3"});
        when(bucket.trySet(any(), anyLong(), any())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");
        clearInvocations(bucket);

        aspect.around(pjp);

        ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
        verify(bucket).trySet(eq("1"), ttl.capture(), eq(java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(10L, ttl.getValue(), "@Idempotent.ttl() 默认值为 10");
    }
}
