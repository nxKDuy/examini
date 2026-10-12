package com.datn.analytics.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology for {@code grading.result.published} v1 (released snapshot). Grading owns the exchange;
 * Analytics owns this queue and its dead-letter queue, separate from the draft-state result queue.
 */
@Configuration
public class PublishedResultEventsConfig {
    public static final String ROUTING_KEY = "grading.result.published.v1";
    public static final String QUEUE = "analytics.grading-published.queue";
    public static final String DEAD_LETTER_QUEUE = "analytics.grading-published.dlq";

    @Bean Queue analyticsGradingPublishedQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DEAD_LETTER_QUEUE).build();
    }

    @Bean Queue analyticsGradingPublishedDeadLetterQueue() { return QueueBuilder.durable(DEAD_LETTER_QUEUE).build(); }

    @Bean Binding analyticsGradingPublishedBinding(@Qualifier("gradingEvents") TopicExchange gradingEvents) {
        return BindingBuilder.bind(analyticsGradingPublishedQueue()).to(gradingEvents).with(ROUTING_KEY);
    }
}
