package com.datn.examsession;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import com.datn.examsession.service.SessionMaintenance;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/session_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false","exam.outbox-poll-ms=3600000","exam.timeout-sweep-ms=3600000","exam.events.max-attempts=3","exam.events.retry-base-ms=1000","exam.events.retry-max-ms=1500"})
class OutboxRetryTest {
    @Autowired SessionMaintenance relay;@Autowired SubmissionEventRepository submissions;@Autowired AttemptEventRepository attempts;@Autowired JdbcTemplate db;
    @MockBean RabbitTemplate rabbit;@MockBean ExamClient exams;
    UUID session;Instant t;
    static void confirm(Object arg,boolean ack){((CorrelationData)arg).getFuture().complete(new CorrelationData.Confirm(ack,ack?null:"rejected"));}
    @BeforeEach void setup(){
        attempts.deleteAll();db.update("update submission_events set delivered=true"); // test DB is shared with other classes
        session=UUID.randomUUID();t=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        db.update("insert into exam_sessions(id,exam_id,user_id,start_time,status,expires_at,attempt_number) values (?,?,?,now(),'SUBMITTED',now()+interval '1 hour',1)",session,UUID.randomUUID(),UUID.randomUUID());
        submissions.saveAndFlush(new SubmissionEvent(session));
    }
    @Test void submissionNackBacksOffThenDeliversWithSameSession(){
        doAnswer(c->{confirm(c.getArgument(3),false);return null;}).when(rabbit).convertAndSend(eq(""),eq("exam.submitted.queue"),any(Object.class),any(CorrelationData.class));
        relay.deliver(t);
        var row=submissions.findById(session).orElseThrow();assertFalse(row.isDelivered());assertEquals(1,row.getAttemptCount());
        assertEquals(t.plusMillis(1000),row.getNextAttemptAt());assertEquals("NACK: rejected",row.getLastError());
        relay.deliver(t.plusMillis(999));assertEquals(1,submissions.findById(session).orElseThrow().getAttemptCount()); // backing off
        reset(rabbit);doAnswer(c->{confirm(c.getArgument(3),true);return null;}).when(rabbit).convertAndSend(eq(""),eq("exam.submitted.queue"),any(Object.class),any(CorrelationData.class));
        relay.deliver(t.plusMillis(1000));
        row=submissions.findById(session).orElseThrow();assertTrue(row.isDelivered());assertEquals(2,row.getAttemptCount());assertNull(row.getLastError());
        verify(rabbit).convertAndSend(eq(""),eq("exam.submitted.queue"),eq(session.toString()),any(CorrelationData.class));
    }
    @Test void unroutablePublicLegBacksOffCappedThenParksWhileGradingLegStaysDelivered()throws Exception{
        submissions.deleteById(session);
        UUID eventId=UUID.randomUUID();
        attempts.saveAndFlush(new AttemptEvent(eventId,"exam.attempt.invalidated",session,"{\"sessionId\":\""+session+"\"}",t));
        doAnswer(c->{confirm(c.getArgument(3),true);return null;}).when(rabbit).convertAndSend(eq(""),eq("exam.attempt.invalidated.queue"),any(Object.class),any(CorrelationData.class));
        doAnswer(c->{CorrelationData cd=c.getArgument(3);cd.setReturned(new ReturnedMessage(c.getArgument(2),312,"NO_ROUTE",c.getArgument(0),c.getArgument(1)));confirm(cd,true);return null;})
                .when(rabbit).send(eq("examini.events"),eq("exam.attempt.invalidated"),any(Message.class),any(CorrelationData.class));
        var delays=new ArrayList<Long>();
        for(int i=1;i<=3;i++){
            relay.deliverAttemptEvents(t);var row=attempts.findById(eventId).orElseThrow();
            assertEquals(i,row.getAttemptCount());assertTrue(row.isGradingDelivered());assertFalse(row.isPublicDelivered());
            assertTrue(row.getLastError().startsWith("public leg UNROUTABLE: 312 NO_ROUTE"),row.getLastError());
            if(row.isParked())break;delays.add(Duration.between(t,row.getNextAttemptAt()).toMillis());t=row.getNextAttemptAt();
        }
        assertEquals(List.of(1000L,1500L),delays);
        assertTrue(attempts.findById(eventId).orElseThrow().isParked());
        relay.deliverAttemptEvents(t.plusSeconds(3600));
        verify(rabbit,times(1)).convertAndSend(eq(""),eq("exam.attempt.invalidated.queue"),any(Object.class),any(CorrelationData.class)); // delivered leg is never re-sent
        verify(rabbit,times(3)).send(eq("examini.events"),anyString(),any(Message.class),any(CorrelationData.class));
        var sent=org.mockito.ArgumentCaptor.forClass(Message.class);verify(rabbit,atLeastOnce()).send(anyString(),anyString(),sent.capture(),any(CorrelationData.class));
        assertTrue(sent.getAllValues().stream().allMatch(m->eventId.toString().equals(m.getMessageProperties().getMessageId()))); // stable eventId
    }
}
