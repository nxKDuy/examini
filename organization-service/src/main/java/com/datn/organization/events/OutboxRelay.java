package com.datn.organization.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Publishes {eventId,eventType,eventVersion:1,occurredAt,payload} to topic exchange examini.events (routing key = eventType). */
@Component
@ConditionalOnProperty(name = "organization.events.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    public static final String EXCHANGE = "examini.events";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient db;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    public OutboxRelay(JdbcClient db, RabbitTemplate rabbit, ObjectMapper json) { this.db = db; this.rabbit = rabbit; this.json = json; }

    @Bean
    TopicExchange organizationEventsExchange() { return new TopicExchange(EXCHANGE, true, false); }

    private record Row(UUID eventId, String eventType, String payload, OffsetDateTime occurredAt) {}

    @Scheduled(fixedDelayString = "${organization.events.outbox-poll-ms:1000}", initialDelayString = "${organization.events.outbox-poll-ms:1000}")
    @Transactional
    public void relay() {
        List<Row> pending = db.sql("SELECT event_id,event_type,payload,occurred_at FROM outbox_events WHERE delivered=FALSE "
                + "ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED").query(Row.class).list();
        for (Row event : pending) {
            try {
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("eventId", event.eventId());
                envelope.put("eventType", event.eventType());
                envelope.put("eventVersion", 1);
                envelope.put("occurredAt", event.occurredAt());
                envelope.put("payload", json.readValue(event.payload(), JsonNode.class));
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(event.eventId().toString());
                CorrelationData correlation = new CorrelationData(event.eventId().toString());
                rabbit.send(EXCHANGE, event.eventType(), new Message(json.writeValueAsBytes(envelope), properties), correlation);
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("not confirmed");
                db.sql("UPDATE outbox_events SET delivered=TRUE WHERE event_id=:id").param("id", event.eventId()).update();
            } catch (Exception failure) {
                log.warn("Organization event remains pending eventId={}", event.eventId());
            }
        }
    }
}
