package com.datn.examsession.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXAM_SUBMITTED_QUEUE = "exam.submitted.queue";

    @Bean
    public Queue examSubmittedQueue() {
        return new Queue(EXAM_SUBMITTED_QUEUE, true);
    }
}
