package org.nearslot.framework.mq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 基础设施（ADR-014）：主队列 + 死信交换机 + 死信队列。
 * 生产侧：publisher-confirm + persistent message；消费侧：手动 ack。
 */
@Configuration
public class RabbitMqConfig {
    public static final String EXCHANGE = "nearslot.exchange";
    public static final String DLX = "nearslot.dlx";
    public static final String APPOINTMENT_QUEUE = "nearslot.appointment.queue";
    public static final String APPOINTMENT_DLQ = "nearslot.appointment.dlq";
    public static final String ROUTING_KEY = "appointment.created";

    /** 缓存失效广播交换机（fanout：每实例独占匿名队列，天然广播，ADR-014）。 */
    public static final String INVALIDATION_FANOUT = "nearslot.invalidation.fanout";

    @Bean public FanoutExchange invalidationFanout() { return new FanoutExchange(INVALIDATION_FANOUT, true, false); }

    @Bean public DirectExchange nearslotExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean public DirectExchange deadLetterExchange() { return new DirectExchange(DLX, true, false); }

    @Bean public Queue appointmentQueue() {
        return QueueBuilder.durable(APPOINTMENT_QUEUE)
            .deadLetterExchange(DLX)
            .deadLetterRoutingKey(APPOINTMENT_DLQ)
            .build();
    }
    @Bean public Queue appointmentDlq() { return QueueBuilder.durable(APPOINTMENT_DLQ).build(); }
    @Bean public Binding appointmentBinding() { return BindingBuilder.bind(appointmentQueue()).to(nearslotExchange()).with(ROUTING_KEY); }
    @Bean public Binding dlqBinding() { return BindingBuilder.bind(appointmentDlq()).to(deadLetterExchange()).with(APPOINTMENT_DLQ); }
}
