package com.datn.analytics.ingest;

import com.datn.analytics.config.PublishedResultEventsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PublishedResultListener {
    private final PublishedResultProjector projector;
    private final ObjectMapper json;

    @RabbitListener(queues = PublishedResultEventsConfig.QUEUE)
    public void onEvent(Message message) {
        try {
            var envelope = EventEnvelope.parse(json, message.getBody(), message.getMessageProperties().getReceivedRoutingKey(),
                    Set.of(EventEnvelope.RESULT_PUBLISHED), ".v1");
            if (!projector.apply(envelope))
                log.debug("Ignored duplicate/stale published result {}", envelope.eventId());
        } catch (ResultProjector.InvalidEventException invalid) {
            log.warn("Rejected grading.result.published message: {}", invalid.getMessage());
            throw new AmqpRejectAndDontRequeueException(invalid.getMessage(), invalid);
        }
    }
}
