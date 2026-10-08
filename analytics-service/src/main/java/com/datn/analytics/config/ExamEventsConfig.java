package com.datn.analytics.config;

import com.datn.analytics.ingest.EventEnvelope;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Analytics-owned queue on the shared examini.events exchange (Exam/Session own the producers). */
@Configuration
public class ExamEventsConfig {
    public static final String EXCHANGE = "examini.events";
    public static final String QUEUE = "analytics.exam-events.queue";
    public static final String DEAD_LETTER_QUEUE = "analytics.exam-events.dlq";

    @Bean TopicExchange examEventsExchange() { return new TopicExchange(EXCHANGE, true, false); }

    @Bean Queue analyticsExamEventsQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DEAD_LETTER_QUEUE).build();
    }

    @Bean Queue analyticsExamEventsDeadLetterQueue() { return QueueBuilder.durable(DEAD_LETTER_QUEUE).build(); }

    @Bean Declarables analyticsExamEventsBindings() {
        return new Declarables(EventEnvelope.HANDLED.stream()
                .map(type -> BindingBuilder.bind(analyticsExamEventsQueue()).to(examEventsExchange()).with(type))
                .toArray(Declarable[]::new));
    }
}
