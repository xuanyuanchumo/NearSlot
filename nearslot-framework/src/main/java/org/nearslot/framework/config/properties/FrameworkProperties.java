package org.nearslot.framework.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 基础设施调优参数（`nearslot.framework.*`）。
 *
 * 这些是**运维可调的技术参数**，不是业务语义（C-ARCH-02 仍然成立）：
 * · 重投次数/退避基数决定故障恢复速度与消息放大倍数；
 * · 缓存重建锁 TTL 决定 Redis 抖动时的回源压力。
 * 不同环境（本地 vs 生产）合理值不同，写死等于把调优空间锁死。
 *
 * 注意：Redis **键前缀**、MQ 交换机/队列**名称**不在此列 ——
 * 它们是命名规范与拓扑契约，可配置化只会让跨环境排查变难。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.framework")
public class FrameworkProperties {

    /** 延迟队列单条消息最多重投次数（超过转死信）。 */
    private int delayMaxRedeliver = 3;

    /** 延迟队列重投退避基数（秒），按 attempt 递增：base, base*2, base*4。 */
    private long delayRedeliverBaseSeconds = 10L;

    /** MQ 消费最大重试次数（超过进死信）。 */
    private int mqMaxRetry = 3;

    /** MQ 投递延迟门阈值（毫秒）：消息滞留超过此值即视为过期。 */
    private long mqMessageDelayMs = 10000L;

    /** 缓存重建互斥锁 TTL（秒）。 */
    private long cacheRebuildLockSeconds = 5L;

    /** 缓存重建等待重读 L2 的时长（毫秒）。 */
    private long cacheRebuildWaitMillis = 50L;

    /** 本地缓存（Caffeine L1）TTL（分钟）。 */
    private long cacheL1TtlMinutes = 20L;
}
