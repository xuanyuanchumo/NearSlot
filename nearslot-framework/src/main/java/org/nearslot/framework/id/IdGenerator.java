package org.nearslot.framework.id;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

/**
 * 分布式 ID 生成（ADR-016：成熟库优先，绝不手写雪花）。
 *
 * 直接委托 MyBatis-Plus `IdWorker`：自带 时间戳+workerId+datacenterId+序列，
 * 保证多实例不撞号、单调递增、可按时间回推。
 *
 * 为何要有这层薄封装（而不是各处直接调 IdWorker）：
 * 1. 收敛依赖点——将来换号段/Redis 方案只改一处；
 * 2. 明确语义——orderId 与 traceId 必须**分别取号**（research/09b §5 纠错点），
 *    此处提供 nextId() 供调用方各自调用，杜绝 `orderId = traceId = nextId()` 的错误写法。
 */
public final class IdGenerator {

    private IdGenerator() {}

    /** 取一个新 ID（雪花）。 */
    public static long nextId() {
        return IdWorker.getId();
    }

    /** 取字符串形式（前端大整数精度安全，ADR-012）。 */
    public static String nextIdStr() {
        return String.valueOf(nextId());
    }
}
