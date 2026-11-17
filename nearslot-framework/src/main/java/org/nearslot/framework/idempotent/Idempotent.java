package org.nearslot.framework.idempotent;
import java.lang.annotation.*;
/** 幂等注解（SpEL key + TTL；业务异常自动删标记）。 */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
public @interface Idempotent { String key(); long ttl() default 10; }
