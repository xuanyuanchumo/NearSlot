package org.nearslot.framework.mq;

import org.nearslot.framework.config.properties.FrameworkProperties;
import org.springframework.amqp.core.Message;

/**
 * RabbitMQ 消费模板（ADR-014）：幂等占位 → 业务 → 失败登记 三段式。
 *
 * 能力边界（评审 C-005 如实声明，2026-11-18）：
 * 本类只实现「投递延迟门 + 幂等占位 + 业务执行 + 失败登记回调」。
 * x-retry-count 重试计数与死信队列转投**并未实现**——此前注释声称"超限进死信"是纸面描述。
 * 当前生产代码没有消费者继承本类（仅单测使用），故无运行时误导；现在如实写明，
 * 避免后续接入方照错误注释实现。
 *
 * 若后续需要重试/死信：由 broker 的 x-dead-letter-exchange 实现，
 * 或在本 handle() 读 HEADER_RETRY 计数转投——二选一，届时同步更新本注释。
 *
 * 延迟门阈值来自 {@link FrameworkProperties#getMqMessageDelayMs()}；
 * 消息头名称是跨服务协议常量，不属于调参。
 */
public abstract class AbstractRabbitConsumer<T> {

    protected static final String HEADER_PRODUCER_TIME = "producerTime";
    protected static final String HEADER_RETRY = "x-retry-count";

    /** 基础设施调优参数（由子类通过构造器传入）。 */
    protected final FrameworkProperties frameworkProperties;

    protected AbstractRabbitConsumer(FrameworkProperties frameworkProperties) {
        this.frameworkProperties = frameworkProperties;
    }

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
        if (producerTime != null
                && System.currentTimeMillis() - producerTime > frameworkProperties.getMqMessageDelayMs()) {
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
