package com.datn.exam;

import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.client.SessionClient;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.ExamOutboxRepository;
import com.datn.exam.service.ExamEventPublisher;
import com.datn.exam.service.ExamOutboxRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.events.outbox-poll-ms=3600000","exam.events.outbox-initial-delay-ms=3600000","exam.schedule.sweep-ms=3600000","exam.start-admission-reconcile-ms=3600000"})
class OutboxContractTest {
    @Autowired ExamEventPublisher publisher;
    @Autowired ExamOutboxRelay relay;
    @Autowired ExamOutboxRepository outbox;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper json;
    @MockBean QuestionBankClient questions;
    @MockBean SessionClient sessions;

    @Test void publishesStrictV1EnvelopeWithStableIdsAndExactRoutingKeys() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("runRabbitContract"));
        outbox.deleteAll();
        String queueName="exam-scheduling-contract-"+UUID.randomUUID();
        Queue queue=new Queue(queueName,false,false,false);admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("examini.events",true,false)).with("exam.#"));
        Exam exam=Exam.builder().id(UUID.randomUUID()).title("Contract").status(Exam.Status.DRAFT).createdBy(UUID.randomUUID())
                .durationMinutes(30).startAt(Instant.parse("2030-01-01T10:00:00Z")).endAt(Instant.parse("2030-01-01T11:00:00Z"))
                .timezone("UTC").deadlineRule(Exam.DeadlineRule.CAPPED_AT_END).reminderOffsetsMinutes(List.of(1440,15))
                .scheduleRevision(4).lifecycleRevision(2).recipientRevision(7).build();
        publisher.scheduleChanged(exam,"RESCHEDULED",exam.getOwnerUserId());
        exam.setStatus(Exam.Status.PUBLISHED);publisher.lifecycleChanged(exam,"MANUAL",exam.getOwnerUserId());
        publisher.recipientsChanged(exam,"JOINED",exam.getOwnerUserId());
        Set<UUID> persistedIds=new HashSet<>();outbox.findAll().forEach(row->persistedIds.add(row.getEventId()));
        relay.relay();
        Set<String> types=new HashSet<>();Set<UUID> deliveredIds=new HashSet<>();
        for(int i=0;i<3;i++) {
            var message=rabbit.receive(queueName,5000);assertNotNull(message);
            var envelope=json.readTree(message.getBody());assertEquals(1,envelope.path("eventVersion").asInt());
            UUID eventId=UUID.fromString(envelope.path("eventId").asText());assertEquals(eventId.toString(),message.getMessageProperties().getMessageId());
            assertTrue(persistedIds.contains(eventId));assertTrue(deliveredIds.add(eventId));assertFalse(envelope.path("occurredAt").asText().isBlank());
            types.add(envelope.path("eventType").asText());assertEquals(envelope.path("eventType").asText(),message.getMessageProperties().getReceivedRoutingKey());
        }
        assertEquals(Set.of("exam.schedule.changed","exam.lifecycle.changed","exam.recipients.changed"),types);
        assertTrue(outbox.findAll().stream().allMatch(row->row.isDelivered()));admin.deleteQueue(queueName);
    }
}
