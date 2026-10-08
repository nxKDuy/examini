package com.datn.examsession.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXAM_SUBMITTED_QUEUE = "exam.submitted.queue";

    public static final String ATTEMPT_INVALIDATED_QUEUE = "exam.attempt.invalidated.queue";
    @Bean public Queue attemptInvalidatedQueue(){return new Queue(ATTEMPT_INVALIDATED_QUEUE,true);}
    @Bean public org.springframework.amqp.core.TopicExchange examEventsExchange(){return new org.springframework.amqp.core.TopicExchange("examini.events",true,false);}
    @Bean
    public Queue examSubmittedQueue() {
        return new Queue(EXAM_SUBMITTED_QUEUE, true);
    }
}
