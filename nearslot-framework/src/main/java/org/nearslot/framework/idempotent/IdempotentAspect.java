package org.nearslot.framework.idempotent;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.redisson.api.RedissonClient;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/**
 * 幂等切面（ADR-016：基于 Redisson RBucket trySet，天然原子）。
 * 业务异常时释放标记，避免异常占用幂等键（PickNear IdempotentAspect 实证）。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {
    private static final String PREFIX = "nearslot:idem:";
    private final RedissonClient redisson;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(org.nearslot.framework.idempotent.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Idempotent anno = sig.getMethod().getAnnotation(Idempotent.class);
        EvaluationContext ctx = new StandardEvaluationContext();
        String[] names = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        // 防御：若编译未开 -parameters，getParameterNames() 返回 null。
        // 此时用 arg0/arg1... 兜底，并在缺乏注解参数名时直接放行而非抛 NPE（实测踩坑）。
        if (names == null) {
            for (int i = 0; i < args.length; i++) ctx.setVariable("arg" + i, args[i]);
        } else {
            for (int i = 0; i < args.length; i++) ctx.setVariable(names[i], args[i]);
        }
        String key = PREFIX + parser.parseExpression(anno.key()).getValue(ctx, String.class);

        boolean first = redisson.getBucket(key).trySet("1", anno.ttl(), TimeUnit.SECONDS);
        if (!first) throw new BaseException(BaseCode.IDEMPOTENT_HIT);
        boolean success = false;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } finally {
            // ============================================================
            // 缺陷修复（2026-11-17 审计，Major）：
            // 此前只在 catch (BaseException) 时删除幂等标记，
            // 任何 RuntimeException / DB 异常 / Error 都会让幂等键占满整个 TTL，
            // 用户重试被 42902「重复提交」拒绝——把一次系统故障放大成用户不可用。
            //
            // 正确语义：只有**成功**才保留标记（成功后的重复提交才该被拦截）；
            // 失败必须释放，允许用户重试。
            // ============================================================
            if (!success) {
                redisson.getBucket(key).delete();
            }
        }
    }
}
