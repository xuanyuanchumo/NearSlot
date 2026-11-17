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
        try {
            return pjp.proceed();
        } catch (BaseException e) {
            redisson.getBucket(key).delete();
            throw e;
        }
    }
}
