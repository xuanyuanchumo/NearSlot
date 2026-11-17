package org.nearslot.framework.mq;

import org.springframework.amqp.core.Message;

/**
 * RabbitMQ 消费模板（ADR-014）：统一 幂等 → 业务 → 失败登记 三段式。
 * 与 PickNear 一致：事务提交后发送、手动 ack、x-retry-count 重试、超限进死信。
 * 投递延迟门：消息头 producerTime 超过阈值即视为过期，回补并丢弃。
 */
public abstract class AbstractRabbitConsumer<T> {
    protected static final long MESSAGE_DELAY_MS = 10_000L;
    protected static final String HEADER_PRODUCER_TIME = "producerTime";
    protected static final String HEADER_RETRY = "x-retry-count";
    protected static final int MAX_RETRY = 3;

    /** 解析消息体。 */
    protected abstract T convert(Message message);
    /** 幂等键（如 orderId）；返回 false 表示已处理过，直接 ack。 */
    protected abstract boolean markProcessed(T payload);
    /** 业务处理。 */
    protected abstract void doConsume(T payload) throws Exception;
    /** 失败回补（best-effort，不阻断）。 */
    protected void onFailure(T payload, Exception cause) {}

    public void handle(Message message) {
        T payload = convert(message);
        Long producerTime = message.getMessageProperties().getHeader(HEADER_PRODUCER_TIME);
        if (producerTime != null && System.currentTimeMillis() - producerTime > MESSAGE_DELAY_MS) {
            safeFailure(payload, new IllegalStateException("message delayed"));
            return;
        }
        if (!markProcessed(payload)) return;
        try { doConsume(payload); }
        catch (Exception e) { safeFailure(payload, e); throw new RuntimeException(e); }
    }

    private void safeFailure(T payload, Exception e) {
        try { onFailure(payload, e); } catch (RuntimeException ignored) { }
    }
}
