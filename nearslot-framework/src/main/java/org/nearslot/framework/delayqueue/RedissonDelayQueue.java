package org.nearslot.framework.delayqueue;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 延迟队列（ADR-016：Redisson RDelayedQueue，数据落 Redis，进程重启不丢，优于自研 DelayQueue）。
 * 注意：延迟队列不保证 exactly-once，消费端仍需幂等 + 对账兜底。
 */
@Component
public class RedissonDelayQueue {
    private final RedissonClient redisson;
    public RedissonDelayQueue(RedissonClient redisson) { this.redisson = redisson; }

    public void offer(String queueName, Object payload, long delaySeconds) {
        RBlockingQueue<Object> blocking = redisson.getBlockingQueue(queueName);
        RDelayedQueue<Object> delayed = redisson.getDelayedQueue(blocking);
        delayed.offer(payload, delaySeconds, TimeUnit.SECONDS);
    }

    /** 启动消费线程（幂等由业务侧保证）。 */
    public void startConsumer(String queueName, Consumer<Object> handler) {
        RBlockingQueue<Object> blocking = redisson.getBlockingQueue(queueName);
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Object payload = blocking.take();
                    handler.accept(payload);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // 消费失败：依赖业务幂等与对账补偿，不阻塞后续消息
                }
            }
        }, "delay-queue-" + queueName);
        t.setDaemon(true);
        t.start();
    }
}
