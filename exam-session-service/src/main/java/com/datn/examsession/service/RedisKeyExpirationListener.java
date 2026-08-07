package com.datn.examsession.service;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RedisKeyExpirationListener extends KeyExpirationEventMessageListener {

    private final SessionService sessionService;

    public RedisKeyExpirationListener(RedisMessageListenerContainer listenerContainer, SessionService sessionService) {
        super(listenerContainer);
        this.sessionService = sessionService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        // exam_session:timeout:{sessionId}
        if (expiredKey.startsWith("exam_session:timeout:")) {
            String sessionIdStr = expiredKey.split(":")[2];
            try {
                UUID sessionId = UUID.fromString(sessionIdStr);
                // Gọi auto submit
                sessionService.autoSubmitExam(sessionId);
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
    }
}
