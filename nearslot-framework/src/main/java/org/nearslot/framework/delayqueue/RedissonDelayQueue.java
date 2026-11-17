package org.nearslot.framework.delayqueue;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 延迟队列（ADR-016：Redisson RDelayedQueue，数据落 Redis，进程重启不丢，优于自研 DelayQueue）。
 *
 * 注意：延迟队列不保证 exactly-once，消费端仍需幂等 + 对账兜底。
 *
 * **2026-11-17 审计修复（Major）**：此前 catch(RuntimeException) 是**空块**——
 * 而 blocking.take() 已经把消息移出队列，等于消费失败即消息永久丢失，
 * 既不重投也无死信、连日志都没有。Hold 超时消息一旦丢失，
 * 该订单永远停在 PENDING（余量不回收），线上根本发现不了。
 *
 * 现改为：失败 -> 记 error 日志 -> 带次数计数与退避**重投**（上限 3 次）
 *      -> 超过上限转死信队列（queueName + ":dlq"）留待人工与对账处理。
 */
@Slf4j
@Component
public class RedissonDelayQueue {

    /** 单条消息最多重投次数（超过转死信，避免毒消息无限循环）。 */
    private static final int MAX_REDELIVER = 3;
    /** 重投退避基数（秒），按 attempt 递增：10s / 20s / 40s。 */
    private static final long REDELIVER_BASE_SECONDS = 10L;
    /** 死信队列后缀。 */
    private static final String DLQ_SUFFIX = ":dlq";

    private final RedissonClient redisson;

    public RedissonDelayQueue(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public void offer(String queueName, Object payload, long delaySeconds) {
        RBlockingQueue<Object> blocking = redisson.getBlockingQueue(queueName);
        RDelayedQueue<Object> delayed = redisson.getDelayedQueue(blocking);
        delayed.offer(payload, delaySeconds, TimeUnit.SECONDS);
    }

    /** 启动消费线程（幂等仍由业务侧保证；此处只负责「不丢消息」）。 */
    public void startConsumer(String queueName, Consumer<Object> handler) {
        RBlockingQueue<Object> blocking = redisson.getBlockingQueue(queueName);
        // 重试计数放进程内：消息体已落 Redis，计数丢失最坏是多试几次，业务幂等可承受；
        // 若用 Redis 计数会为每条消息引入额外读写放大。
        Map<Object, Integer> attempts = new ConcurrentHashMap<>();
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Object payload;
                try {
                    payload = blocking.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // take() 本身失败（连接抖动等）：消息仍在队列，记录后继续
                    log.error("延迟队列取消息失败 queue={}", queueName, e);
                    continue;
                }
                try {
                    handler.accept(payload);
                    attempts.remove(payload);
                } catch (RuntimeException e) {
                    handleFailure(queueName, payload, attempts, e);
                }
            }
        }, "delay-queue-" + queueName);
        t.setDaemon(true);
        t.start();
    }

    /** 消费失败：计数 -> 未超限则退避重投；超限转死信。 */
    private void handleFailure(String queueName, Object payload, Map<Object, Integer> attempts,
                               RuntimeException cause) {
        Integer previous = attempts.get(payload);
        int attempt = previous == null ? 1 : previous + 1;
        attempts.put(payload, attempt);
        if (attempt > MAX_REDELIVER) {
            attempts.remove(payload);
            // 死信：不再重试，但必须留下可查证据，供对账/人工补偿
            redisson.getBlockingQueue(queueName + DLQ_SUFFIX).offer(payload);
            log.error("延迟队列消息进入死信 queue={} attempts={} payload={}",
                queueName, attempt - 1, payload, cause);
            return;
        }
        long backoff = REDELIVER_BASE_SECONDS * (1L << (attempt - 1));
        offer(queueName, payload, backoff);
        log.error("延迟队列消费失败，{}s 后第 {} 次重投 queue={} payload={}",
            backoff, attempt, queueName, payload, cause);
    }
}
