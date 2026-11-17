package org.nearslot.shop.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.CacheManager;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * 缓存失效广播消费者（FR-2.2）。
 *
 * 每实例绑定一个**独占匿名队列**到 fanout，实现"广播到所有实例"：
 *   - exclusive=true：仅本连接可见，断开自动删除；
 *   - autoDelete=true：无消费者时删除。
 * 扩缩容天然安全，无需维护实例清单。
 *
 * **条件装配**（实测踩坑）：`@ConditionalOnProperty` 必须标在**类上**，
 * 标在 `@RabbitListener` 方法上无效——注解在 Bean 创建前解析，
 * 此时队列 Bean 已被移除，导致 SpEL `#{invalidationAnonymousQueue.name}`
 * 解析失败（Expression parsing failed）直接启动崩溃。
 * 因此整个类（含内部配置类）统一用类级条件，一条开关同时控制
 * 队列声明与监听器注册。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nearslot.mq.invalidation-enabled",
                       havingValue = "true", matchIfMissing = true)
public class CacheInvalidationConsumer {

    private final CacheManager cacheManager;
    private final DistributedLockTemplate lockTemplate;

    /** 每个实例启动时声明自己的匿名队列，并绑定 fanout。 */
    @Configuration
    static class AnonymousQueueConfig {
        @Bean
        public Queue invalidationAnonymousQueue() {
            return QueueBuilder.nonDurable().exclusive().autoDelete().build();
        }

        @Bean
        public Binding invalidationAnonymousBinding(Queue invalidationAnonymousQueue,
                                                    FanoutExchange invalidationFanout) {
            return BindingBuilder.bind(invalidationAnonymousQueue).to(invalidationFanout);
        }
    }

    /** 收到广播即失效本地 L1（写锁串行化，避免与重建并发冲突）。 */
    @RabbitListener(queues = "#{invalidationAnonymousQueue.name}")
    public void onInvalidate(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) return;
        lockTemplate.withWriteLock("nearslot:lock:cache:" + cacheKey, () -> {
            cacheManager.invalidate(cacheKey);
            log.info("收到广播并失效本地缓存 key={}", cacheKey);
            return null;
        });
    }
}
