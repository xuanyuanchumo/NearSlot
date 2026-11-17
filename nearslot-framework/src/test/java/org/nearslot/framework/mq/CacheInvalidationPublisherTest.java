package org.nearslot.framework.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存失效广播生产者单测（FR-2.2）。
 * 重点覆盖：fanout 空路由键约定、MQ 不可用时返回 false 让调用方本地降级。
 */
class CacheInvalidationPublisherTest {

    private RabbitTemplate rabbitTemplate;
    private CacheInvalidationPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisher = new CacheInvalidationPublisher(rabbitTemplate);
    }

    @Test
    @DisplayName("正常投递 -> 发往 fanout 交换机、路由键为空串，返回 true")
    void publishSendsToFanout() {
        assertTrue(publisher.publish("shop:1"));

        verify(rabbitTemplate).convertAndSend(
            eq(RabbitMqConfig.INVALIDATION_FANOUT), eq(""), eq((Object) "shop:1"));
        assertEquals("nearslot.invalidation.fanout", RabbitMqConfig.INVALIDATION_FANOUT);
    }

    @Test
    @DisplayName("MQ 不可用（AmqpException）-> 返回 false，不抛异常（调用方据此本地降级）")
    void publishReturnsFalseOnAmqpException() {
        doThrow(new AmqpException("broker down"))
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertFalse(publisher.publish("shop:1"), "MQ 故障必须降级为 false，不能让主流程抛错");
    }

    @Test
    @DisplayName("其他运行时异常同样降级为 false（不区分异常类型，保证调用方语义一致）")
    void publishReturnsFalseOnRuntimeException() {
        doThrow(new IllegalStateException("unexpected"))
            .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        assertFalse(publisher.publish("shop:1"));
    }

    @Test
    @DisplayName("key 为空串也能安全投递（不做额外校验）")
    void publishEmptyKey() {
        assertTrue(publisher.publish(""));

        verify(rabbitTemplate).convertAndSend(eq(RabbitMqConfig.INVALIDATION_FANOUT), eq(""), eq((Object) ""));
    }
}
