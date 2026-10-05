package com.datn.analytics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

/**
 * Topology for the PROPOSED {@code grading.result.recorded} v1 contract.
 * Grading owns the exchange; Analytics owns its queue and dead-letter queue.
 * Declarations are idempotent, so either side may start first.
 */
@Configuration
public class GradingEventsConfig {
    public static final String EXCHANGE = "examini.grading.events";
    public static final String ROUTING_KEY = "grading.result.recorded.v1";
    public static final String QUEUE = "analytics.grading-result.queue";
    public static final String DEAD_LETTER_QUEUE = "analytics.grading-result.dlq";

    @Bean TopicExchange gradingEvents() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean Queue analyticsGradingResultQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange("").deadLetterRoutingKey(DEAD_LETTER_QUEUE).build();
    }

    @Bean Queue analyticsGradingResultDeadLetterQueue() { return QueueBuilder.durable(DEAD_LETTER_QUEUE).build(); }

    @Bean Binding analyticsGradingResultBinding() {
        return BindingBuilder.bind(analyticsGradingResultQueue()).to(gradingEvents()).with(ROUTING_KEY);
    }

    @Bean MessageConverter gradingEventConverter(ObjectMapper json) { return new Jackson2JsonMessageConverter(json); }

    /** Contract violations go straight to the dead-letter queue; only infrastructure failures are retried. */
    @Bean RabbitRetryTemplateCustomizer contractViolationsAreNotRetried(RabbitProperties rabbit) {
        int attempts = rabbit.getListener().getSimple().getRetry().getMaxAttempts();
        return (target, template) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER)
                template.setRetryPolicy(new SimpleRetryPolicy(attempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true));
        };
    }
}
