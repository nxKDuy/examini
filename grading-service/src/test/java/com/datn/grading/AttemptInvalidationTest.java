package com.datn.grading;

import com.datn.grading.client.*;
import com.datn.grading.entity.ExamResult;
import com.datn.grading.repository.*;
import com.datn.grading.service.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/grading_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false"})
class AttemptInvalidationTest {
    @Autowired GradingService service;@Autowired ExamResultRepository results;@Autowired ResultPublicationRepository publications;
    @Autowired GradingAuditRepository audits;@Autowired GradingOutboxRepository outbox;
    @MockBean ExamClient exams;@MockBean ExamSessionClient sessions;@MockBean GeminiClient ai;@MockBean RabbitTemplate rabbit;
    UUID session,exam,user,teacher;

    @BeforeEach void setup() {
        outbox.deleteAll();audits.deleteAll();publications.deleteAll();results.deleteAll();
        session=UUID.randomUUID();exam=UUID.randomUUID();user=UUID.randomUUID();teacher=UUID.randomUUID();
        when(exams.resultPolicy(eq(exam),eq("test-internal"))).thenReturn(new ExamClient.ResultPolicy("CLOSED",new ExamClient.Policy(true,true,true,true,false,false)));
        when(exams.permission(eq(exam),any(),eq(true),eq("test-internal"))).thenReturn("OWNER");
        var q=new ExamClient.Question(UUID.randomUUID(),1,"MULTIPLE_CHOICE","Q",List.of(new ExamClient.Option(UUID.randomUUID(),"Yes",0,true),new ExamClient.Option(UUID.randomUUID(),"No",1,false)),BigDecimal.valueOf(2),null,0);
        when(exams.key(exam,"test-internal")).thenReturn(List.of(q));
    }
    void submitted(Instant invalidatedAt,String reason) {
        var now=LocalDateTime.now();
        when(sessions.get(session,"test-internal")).thenReturn(new ExamSessionClient.SessionDetailDto(session,exam,user,"SUBMITTED","Exam",1,now.minusMinutes(5),now,List.of(),invalidatedAt,reason));
        service.processSubmittedExam(session.toString());
    }
    String event(Instant at,String reason){return "{\"sessionId\":\""+session+"\",\"examId\":\""+exam+"\",\"userId\":\""+user+"\",\"attemptNumber\":1,\"status\":\"SUBMITTED\",\"reason\":\""+reason+"\",\"actor\":\""+teacher+"\",\"invalidatedAt\":\""+at+"\"}";}
    static int status(Runnable r){return assertThrows(ResponseStatusException.class,r::run).getStatusCode().value();}

    @Test void invalidationIsMirroredKeepsPublicationAndNeverTurnsValidAgain() {
        submitted(null,null);
        var result=results.findBySessionId(session).orElseThrow();
        assertEquals(ExamResult.Status.NEEDS_REVIEW.equals(result.getStatus())||result.getStatus()==ExamResult.Status.GRADED,true);
        long revision=result.getResultRevision();
        Instant at=Instant.now();
        service.processAttemptInvalidated(event(at,"Cheating confirmed"));
        service.processAttemptInvalidated(event(at,"Cheating confirmed")); // duplicate delivery
        var invalid=results.findBySessionId(session).orElseThrow();
        assertTrue(invalid.isInvalidated());assertEquals("Cheating confirmed",invalid.getInvalidationReason());assertEquals(revision,invalid.getResultRevision());
        assertEquals(1,audits.findAll().stream().filter(a->"RESULT_INVALIDATED".equals(a.getAction())).count());
        assertEquals(409,status(()->service.release(invalid.getId(),invalid.getResultRevision(),teacher)));
        assertEquals(409,status(()->service.finalizeResult(invalid.getId(),invalid.getResultRevision(),teacher)));
        assertEquals(409,status(()->service.beginRegrade(invalid.getId(),new com.datn.grading.dto.SubmissionDto.RegradeRequest(invalid.getResultRevision(),"Retry","note"),teacher)));
        var student=service.student(session,user);
        assertEquals(Boolean.TRUE,student.invalidated());assertEquals("Cheating confirmed",student.invalidationReason());
        assertEquals(1,publications.findByResultIdOrderByResultRevisionAsc(invalid.getId()).size()); // published history retained
        var overview=service.overview(exam,teacher);
        assertEquals(0,overview.totalSubmissions());assertEquals(1,overview.invalidated()); // excluded from valid aggregates
        assertTrue(service.getSubmissions(exam,null,null,org.springframework.data.domain.PageRequest.of(0,10),teacher).getContent().get(0).invalidated());
    }

    @Test void resultCreatedAfterInvalidationIsBornInvalidAndNotReleased() {
        submitted(Instant.now(),"Invalidated before grading");
        var result=results.findBySessionId(session).orElseThrow();
        assertTrue(result.isInvalidated());assertNull(result.getReleasedRevision());
        assertEquals(0,publications.findByResultIdOrderByResultRevisionAsc(result.getId()).size());
    }

    @Test void invalidationEventForUnknownSessionIsHarmlessAndMalformedIsRejected() {
        service.processAttemptInvalidated(event(Instant.now(),"No result yet"));
        assertTrue(results.findBySessionId(session).isEmpty());
        assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->service.processAttemptInvalidated("{not json"));
        assertThrows(org.springframework.amqp.AmqpRejectAndDontRequeueException.class,()->service.processAttemptInvalidated("{\"sessionId\":\"x\"}"));
    }
}
