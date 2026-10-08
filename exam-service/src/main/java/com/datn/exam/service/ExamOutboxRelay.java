package com.datn.exam.service;

import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.ExamOutboxEvent;
import com.datn.exam.repository.ExamOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor
public class ExamOutboxRelay {
    private final ExamOutboxRepository events;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExamOutboxRelay.class);

    @Scheduled(fixedDelayString="${exam.events.outbox-poll-ms:1000}",
            initialDelayString="${exam.events.outbox-initial-delay-ms:1000}")
    @Transactional
    public void relay() {
        for(ExamOutboxEvent event:events.findTop100ByDeliveredFalseOrderByOccurredAtAsc()) {
            try {
                JsonNode payload=json.readTree(event.getPayload());
                var envelope=new ScheduleDto.NotificationEnvelope(event.getEventId(),event.getEventType(),1,event.getOccurredAt(),payload);
                MessageProperties properties=new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.getEventId().toString());
                byte[] body=json.writeValueAsBytes(envelope);
                var correlation=new CorrelationData(event.getEventId().toString());
                rabbit.send("examini.events",event.getEventType(),new Message(body,properties),correlation);
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(!confirm.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("Event was not confirmed");
                event.markDelivered();
            } catch(Exception failure) {
                log.warn("Exam event remains pending for eventId={}",event.getEventId());
            }
        }
    }
}
