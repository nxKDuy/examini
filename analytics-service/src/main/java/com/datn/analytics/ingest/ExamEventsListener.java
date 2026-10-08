package com.datn.analytics.ingest;

import com.datn.analytics.config.ExamEventsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExamEventsListener {
    private final ParticipationProjector projector;
    private final ObjectMapper json;

    @RabbitListener(queues = ExamEventsConfig.QUEUE)
    public void onEvent(Message message) {
        try {
            var envelope = EventEnvelope.parse(json, message.getBody(), message.getMessageProperties().getReceivedRoutingKey());
            if (!projector.apply(envelope))
                log.debug("No projection change for {} {}", envelope.eventType(), envelope.eventId());
        } catch (ResultProjector.InvalidEventException invalid) {
            log.warn("Rejected examini.events message: {}", invalid.getMessage());
            throw new AmqpRejectAndDontRequeueException(invalid.getMessage(), invalid);
        }
    }
}
