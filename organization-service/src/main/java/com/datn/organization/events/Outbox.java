package com.datn.organization.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Transactional outbox: rows are written inside the business transaction and relayed to examini.events. */
@Component
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;

    public Outbox(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }

    public void add(String eventType, Map<String, Object> payload) {
        try {
            db.sql("INSERT INTO outbox_events(event_id,event_type,payload,occurred_at) VALUES (:id,:type,:payload,:at)")
                    .param("id", UUID.randomUUID()).param("type", eventType).param("payload", json.writeValueAsString(payload))
                    .param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
