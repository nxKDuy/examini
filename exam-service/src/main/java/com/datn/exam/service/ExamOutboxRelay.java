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

@Component @RequiredArgsConstructor
public class ExamOutboxRelay {
    private final ExamOutboxRepository events;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExamOutboxRelay.class);
    @org.springframework.beans.factory.annotation.Value("${exam.events.retry-base-ms:2000}") private long retryBaseMs=2000;
    @org.springframework.beans.factory.annotation.Value("${exam.events.retry-max-ms:900000}") private long retryMaxMs=900000;
    @org.springframework.beans.factory.annotation.Value("${exam.events.max-attempts:200}") private int maxAttempts=200;

    @Scheduled(fixedDelayString="${exam.events.outbox-poll-ms:1000}",
            initialDelayString="${exam.events.outbox-initial-delay-ms:1000}")
    @Transactional
    public void relay() {relay(java.time.Instant.now());}

    /** Backs off exponentially per row and parks after exam.events.max-attempts; eventId stays stable. */
    @Transactional
    public void relay(java.time.Instant now) {
        for(ExamOutboxEvent event:events.findDue(now,org.springframework.data.domain.PageRequest.of(0,100))) {
            event.attempted();
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
                OutboxRetry.confirmed(correlation);
                event.markDelivered();
            } catch(Exception failure) {
                String reason=OutboxRetry.truncate(OutboxRetry.reason(failure));
                boolean park=event.getAttemptCount()>=maxAttempts;
                var next=park?null:now.plusMillis(OutboxRetry.backoffMs(event.getAttemptCount(),retryBaseMs,retryMaxMs));
                event.failed(reason,next,park);
                if(park)log.error("Exam event parked after {} attempts eventId={} eventType={} reason={}",event.getAttemptCount(),event.getEventId(),event.getEventType(),reason);
                else log.warn("Exam event delivery failed attempt={} eventId={} eventType={} nextAttemptAt={} reason={}",event.getAttemptCount(),event.getEventId(),event.getEventType(),next,reason);
            }
        }
    }
}
