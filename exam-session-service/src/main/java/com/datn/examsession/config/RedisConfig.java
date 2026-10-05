package com.datn.examsession.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="exam.redis-expiry-enabled",havingValue="true")
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer keyExpirationListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        // Lắng nghe sự kiện hết hạn key (yêu cầu cấu hình notify-keyspace-events = Ex trên Redis server)
        listenerContainer.addMessageListener(
                (message, pattern) -> {}, // Sẽ đăng ký listener cụ thể sau
                new PatternTopic("__keyevent@*__:expired")
        );
        return listenerContainer;
    }
}
