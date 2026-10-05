package com.datn.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    /** Shared topic exchange for versioned domain events; routing key = eventType. */
    public static final String EVENTS_EXCHANGE = "examini.events";
    public static final String QUEUE = "notification.events.queue";
    public static final String DEAD_LETTER_QUEUE = "notification.events.dlq";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Declarables notificationBindings(TopicExchange eventsExchange, Queue notificationQueue) {
        return new Declarables(com.datn.notification.service.EventCatalog.EVENT_TYPES.stream()
                .map(type -> BindingBuilder.bind(notificationQueue).to(eventsExchange).with(type))
                .toArray(Declarable[]::new));
    }
}
