package org.nearslot.framework.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RabbitMQ 消费模板单测（ADR-014：幂等 → 业务 → 失败登记 三段式）。
 * 重点覆盖：投递延迟门、幂等命中直接返回、业务异常包装上抛且登记失败、
 * 回补钩子自身异常被吞掉（不阻断主流程）。
 */
class AbstractRabbitConsumerTest {

    /** 可观测的测试消费者：记录 convert / markProcessed / doConsume / onFailure 的每一次调用。 */
    static class TestConsumer extends AbstractRabbitConsumer<String> {
        final List<String> converted = new ArrayList<>();
        final List<String> markCalls = new ArrayList<>();
        final List<String> consumed = new ArrayList<>();
        final List<Exception> failures = new ArrayList<>();
        boolean markResult = true;
        RuntimeException markError;
        Exception consumeError;
        RuntimeException failureHookError;

        @Override
        protected String convert(Message message) {
            return new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        protected boolean markProcessed(String payload) {
            markCalls.add(payload);
            if (markError != null) throw markError;
            return markResult;
        }

        @Override
        protected void doConsume(String payload) throws Exception {
            consumed.add(payload);
            if (consumeError != null) throw consumeError;
        }

        @Override
        protected void onFailure(String payload, Exception cause) {
            failures.add(cause);
            if (failureHookError != null) throw failureHookError;
        }
    }

    private static Message msg(String body) {
        MessageProperties props = new MessageProperties();
        return new Message(body.getBytes(), props);
    }

    private static Message msg(String body, Long producerTime) {
        MessageProperties props = new MessageProperties();
        if (producerTime != null) props.setHeader("producerTime", producerTime);
        return new Message(body.getBytes(), props);
    }

    @Test
    @DisplayName("正常消费 -> 幂等占位成功后执行业务，不触发失败登记")
    void happyPath() {
        TestConsumer c = new TestConsumer();

        c.handle(msg("order-1"));

        assertEquals(List.of("order-1"), c.markCalls);
        assertEquals(List.of("order-1"), c.consumed);
        assertTrue(c.failures.isEmpty());
    }

    @Test
    @DisplayName("幂等命中（markProcessed=false）-> 直接返回，不执行业务")
    void idempotentHitSkipsBusiness() {
        TestConsumer c = new TestConsumer();
        c.markResult = false;

        c.handle(msg("order-1"));

        assertEquals(List.of("order-1"), c.markCalls);
        assertTrue(c.consumed.isEmpty(), "已处理过的消息必须直接 ack，不重复执行业务");
        assertTrue(c.failures.isEmpty());
    }

    @Test
    @DisplayName("业务异常 -> 登记失败并包装成 RuntimeException 上抛（触发 MQ 重试/死信）")
    void businessFailureRegistersAndRethrows() {
        TestConsumer c = new TestConsumer();
        IllegalStateException cause = new IllegalStateException("库存不足");
        c.consumeError = cause;

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> c.handle(msg("order-1")));

        assertSame(cause, thrown.getCause(), "原始异常必须作为 cause 保留（便于排查）");
        assertEquals(1, c.failures.size());
        assertSame(cause, c.failures.get(0));
        assertEquals(List.of("order-1"), c.consumed);
    }

    @Test
    @DisplayName("回补钩子自身抛异常 -> 被吞掉，不影响主流程抛出（best-effort）")
    void failureHookErrorIsSwallowed() {
        TestConsumer c = new TestConsumer();
        c.consumeError = new IllegalStateException("业务炸了");
        c.failureHookError = new RuntimeException("回补也炸了");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> c.handle(msg("order-1")));

        assertSame(c.consumeError, thrown.getCause(), "回补失败不能覆盖原始异常");
    }

    @Test
    @DisplayName("投递延迟门：producerTime 超阈值 -> 登记失败并直接丢弃，不执行业务、不抛异常")
    void delayedMessageIsDropped() {
        TestConsumer c = new TestConsumer();
        long tooOld = System.currentTimeMillis() - AbstractRabbitConsumer.MESSAGE_DELAY_MS - 1000;

        assertDoesNotThrow(() -> c.handle(msg("order-1", tooOld)));

        assertTrue(c.markCalls.isEmpty(), "过期消息不应进入幂等占位");
        assertTrue(c.consumed.isEmpty(), "过期消息必须被丢弃");
        assertEquals(1, c.failures.size());
        assertInstanceOf(IllegalStateException.class, c.failures.get(0));
        assertEquals("message delayed", c.failures.get(0).getMessage());
    }

    @Test
    @DisplayName("投递延迟门边界：恰在阈值内 -> 正常消费")
    void freshMessageIsConsumed() {
        TestConsumer c = new TestConsumer();
        long recent = System.currentTimeMillis() - 1000;

        c.handle(msg("order-1", recent));

        assertEquals(List.of("order-1"), c.consumed);
        assertTrue(c.failures.isEmpty());
    }

    @Test
    @DisplayName("无 producerTime 头 -> 不做延迟判定，正常消费")
    void missingProducerTimeIsConsumed() {
        TestConsumer c = new TestConsumer();

        c.handle(msg("order-1", null));

        assertEquals(List.of("order-1"), c.consumed);
        assertTrue(c.failures.isEmpty());
    }

    /** 不覆写 onFailure 的最小消费者：覆盖模板里 onFailure 的默认空实现。 */
    static class MinimalConsumer extends AbstractRabbitConsumer<String> {
        @Override
        protected String convert(Message message) {
            return new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        protected boolean markProcessed(String payload) {
            return true;
        }

        @Override
        protected void doConsume(String payload) {
            throw new IllegalStateException("默认回补钩子场景");
        }
    }

    @Test
    @DisplayName("未覆写 onFailure（默认空实现）-> 业务异常仍正常包装上抛，不抛 NPE")
    void defaultOnFailureIsNoop() {
        MinimalConsumer c = new MinimalConsumer();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> c.handle(msg("order-1")));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    @Test
    @DisplayName("幂等占位自身抛异常 -> 直接上抛，不执行业务（故障可见，不静默吞消息）")
    void markProcessedErrorPropagates() {
        TestConsumer c = new TestConsumer();
        c.markError = new IllegalStateException("redis down");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> c.handle(msg("order-1")));

        assertEquals("redis down", thrown.getMessage());
        assertTrue(c.consumed.isEmpty());
    }
}
