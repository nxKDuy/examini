package com.datn.examsession;

import com.datn.examsession.entity.AttemptEvent;
import com.datn.examsession.service.SessionMaintenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** The public leg must use the shared envelope keys, otherwise Analytics/Notification consumers dead-letter every event. */
class AttemptEnvelopeTest {
    @Test void publicEnvelopeUsesSharedEventTypeAndEventVersionKeys()throws Exception {
        var id=UUID.randomUUID();var at=Instant.parse("2030-03-10T10:00:00Z");
        var event=new AttemptEvent(id,"exam.attempt.invalidated",UUID.randomUUID(),"{\"reason\":\"Public reason\"}",at);
        Method m=SessionMaintenance.class.getDeclaredMethod("envelope",AttemptEvent.class,ObjectMapper.class);m.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Object> envelope=(Map<String,Object>)m.invoke(null,event,new ObjectMapper());
        assertEquals(java.util.List.of("eventId","eventType","eventVersion","occurredAt","payload"),java.util.List.copyOf(envelope.keySet()));
        assertEquals(id,envelope.get("eventId"));assertEquals("exam.attempt.invalidated",envelope.get("eventType"));assertEquals(1,envelope.get("eventVersion"));
        assertEquals(at.toString(),envelope.get("occurredAt"));assertEquals("Public reason",((com.fasterxml.jackson.databind.JsonNode)envelope.get("payload")).get("reason").asText());
        assertFalse(envelope.containsKey("type"));assertFalse(envelope.containsKey("version"));
    }
}
