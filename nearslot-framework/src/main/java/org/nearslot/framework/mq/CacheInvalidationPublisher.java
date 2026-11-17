package org.nearslot.framework.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存失效广播生产者（FR-2.2）。
 *
 * **为何用 fanout + 匿名队列**（docs/planning/phases/phase-2.md §3）：
 * 多个应用实例各自持有 Caffeine L1，本地缓存必须**每个实例都失效**。
 * fanout 会把消息投递给每个绑定队列，每个实例启动时绑定自己的匿名队列
 * （exclusive + autoDelete），实例下线队列自动消失，无需维护成员列表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 广播失效事件。
     *
     * @return true=已投递到 MQ；false=MQ 不可用（调用方需做本地降级）
     *
     * 为何返回布尔而非静默吞掉：MQ 不可用时若不做任何处理，
     * 其他实例会继续返回旧缓存（跨实例脏读）。调用方拿到 false 后
     * 至少应失效**本实例**缓存，保证单实例正确性（实测踩坑：
     * 本地开发未起 RabbitMQ 时，发布时段后缓存一直是旧值）。
     */
    public boolean publish(String cacheKey) {
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.INVALIDATION_FANOUT, "", cacheKey);
            log.info("缓存失效广播已发送 key={}", cacheKey);
            return true;
        } catch (RuntimeException e) {
            log.warn("缓存失效广播失败（MQ 不可用）key={}，调用方需本地降级", cacheKey);
            return false;
        }
    }
}
