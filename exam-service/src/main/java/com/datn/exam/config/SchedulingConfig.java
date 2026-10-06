package com.datn.exam.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;

@Configuration
@EnableScheduling
public class SchedulingConfig {
    @Bean TopicExchange examiniEventsExchange(){return new TopicExchange("examini.events",true,false);}
    @Bean Clock examClock(){return Clock.systemUTC();}
}
