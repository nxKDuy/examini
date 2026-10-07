package com.datn.grading.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String EXAM_SUBMITTED_QUEUE = "exam.submitted.queue";

    @Bean
    public Queue examSubmittedQueue() {
        return new Queue(EXAM_SUBMITTED_QUEUE, true); // durable = true
    }
    @Bean public TopicExchange gradingEventsExchange(){return new TopicExchange("examini.grading.events",true,false);}
    @Bean public TopicExchange notificationEventsExchange(){return new TopicExchange("examini.events",true,false);}
}
