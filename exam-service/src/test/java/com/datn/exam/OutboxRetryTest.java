package com.datn.exam;

import com.datn.exam.client.*;
import com.datn.exam.entity.ExamOutboxEvent;
import com.datn.exam.repository.ExamOutboxRepository;
import com.datn.exam.service.ExamOutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","exam.events.max-attempts=3","exam.events.retry-base-ms=1000","exam.events.retry-max-ms=1500"})
class OutboxRetryTest {
    @Autowired ExamOutboxRelay relay;@Autowired ExamOutboxRepository outbox;@Autowired ObjectMapper json;
    @MockBean RabbitTemplate rabbit;@MockBean QuestionBankClient bank;@MockBean SessionClient sessions;
    @Test void timeoutBacksOffUnroutableParksAndEnvelopeKeepsEventId()throws Exception{
        UUID eventId=UUID.randomUUID();Instant t=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        outbox.deleteAll();outbox.saveAndFlush(new ExamOutboxEvent(eventId,"exam.schedule.changed","{\"examId\":\"x\"}",t));
        // Attempt 1: no confirm arrives (timeout); attempts 2-3: broker returns the mandatory message.
        doNothing().doAnswer(c->{CorrelationData cd=c.getArgument(3);cd.setReturned(new ReturnedMessage(c.getArgument(2),312,"NO_ROUTE",c.getArgument(0),c.getArgument(1)));cd.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
                .when(rabbit).send(eq("examini.events"),eq("exam.schedule.changed"),any(Message.class),any(CorrelationData.class));
        relay.relay(t);var row=outbox.findById(eventId).orElseThrow();
        assertEquals(1,row.getAttemptCount());assertTrue(row.getLastError().startsWith("CONFIRM_TIMEOUT"));assertEquals(t.plusMillis(1000),row.getNextAttemptAt());
        relay.relay(t.plusMillis(500));assertEquals(1,outbox.findById(eventId).orElseThrow().getAttemptCount());
        relay.relay(t.plusMillis(1000));row=outbox.findById(eventId).orElseThrow();
        assertEquals(2,row.getAttemptCount());assertTrue(row.getLastError().startsWith("UNROUTABLE: 312 NO_ROUTE"));assertEquals(t.plusMillis(2500),row.getNextAttemptAt()); // capped at 1500
        relay.relay(t.plusMillis(2500));row=outbox.findById(eventId).orElseThrow();
        assertTrue(row.isParked());assertNull(row.getNextAttemptAt());assertFalse(row.isDelivered());
        relay.relay(t.plusSeconds(3600));
        var sent=org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbit,times(3)).send(eq("examini.events"),eq("exam.schedule.changed"),sent.capture(),any(CorrelationData.class));
        for(var m:sent.getAllValues()){assertEquals(eventId.toString(),m.getMessageProperties().getMessageId());
            var envelope=json.readTree(m.getBody());assertEquals(eventId.toString(),envelope.path("eventId").asText());assertEquals(1,envelope.path("eventVersion").asInt());}
    }
}
