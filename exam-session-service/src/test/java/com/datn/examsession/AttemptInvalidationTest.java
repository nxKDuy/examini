package com.datn.examsession;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.entity.ExamSession;
import com.datn.examsession.repository.*;
import com.datn.examsession.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/session_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false"})
class AttemptInvalidationTest {
    @Autowired SessionService service;@Autowired SessionRepository sessions;@Autowired SessionAnswerRepository answers;
    @Autowired SubmissionEventRepository submissions;@Autowired AttemptEventRepository attemptEvents;@Autowired ObjectMapper json;
    @MockBean ExamClient exams;@MockBean SessionMaintenance maintenance;
    UUID exam,user,other,staff,qid,option;

    @BeforeEach void setup() {
        exam=UUID.randomUUID();user=UUID.randomUUID();other=UUID.randomUUID();staff=UUID.randomUUID();qid=UUID.randomUUID();option=UUID.randomUUID();
        authorizeWithLimit(1,null);
        when(exams.safe(exam,"test-internal")).thenReturn(List.of(new ExamClient.Question(qid,1,"MULTIPLE_CHOICE","Q",List.of(new ExamClient.Option(option,"A",0)),BigDecimal.ONE,0)));
        when(exams.reserveStart(any(),any(),eq("test-internal"))).thenAnswer(c->new ExamClient.StartReservation(c.getArgument(0),UUID.randomUUID(),1));
        when(exams.claimStart(any(),any(),any(),eq("test-internal"))).thenAnswer(c->{UUID admission=c.getArgument(1);Instant now=Instant.now();return new ExamClient.ScheduleAdmission(admission,1,now,now.plusSeconds(600));});
        when(exams.permission(eq(exam),eq(staff),anyBoolean(),eq("test-internal"))).thenReturn("MANAGER");
        when(exams.permission(eq(exam),eq(other),anyBoolean(),eq("test-internal"))).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"Exam permission denied"));
    }
    void authorizeWithLimit(int examLimit,Integer effective) {
        when(exams.authorize(eq(exam),any(),eq("test-internal"))).thenReturn(new ExamClient.ExamDto(exam,"Exam",10,5,examLimit,null,false,false));
        when(exams.attemptLimit(eq(exam),any(),eq("test-internal"))).thenReturn(effective);
    }
    static int status(Runnable r){return assertThrows(ResponseStatusException.class,r::run).getStatusCode().value();}

    @Test void startedEventOncePerStartPublicOnlyAndBackfillIdempotent()throws Exception {
        var first=service.startExam(exam,user);
        assertEquals(first.sessionId(),service.startExam(exam,user).sessionId()); // resume does not emit again
        var started=attemptEvents.findAll().stream().filter(e->e.getSessionId().equals(first.sessionId())&&e.getEventType().equals("exam.attempt.started")).toList();
        assertEquals(1,started.size());var event=started.getFirst();
        assertEquals(SessionService.startedEventId(first.sessionId()),event.getEventId());
        assertTrue(event.isGradingDelivered());assertFalse(event.isPublicDelivered()); // never sent to Grading's invalidation queue
        var payload=json.readTree(event.getPayload());
        assertEquals(first.sessionId().toString(),payload.path("sessionId").asText());assertEquals(exam.toString(),payload.path("examId").asText());
        assertEquals(user.toString(),payload.path("candidateId").asText());assertEquals(1,payload.path("attemptNumber").asInt());
        assertEquals(event.getOccurredAt(),Instant.parse(payload.path("startedAt").asText()));assertFalse(payload.path("expiresAt").isNull());
        service.invalidate(first.sessionId(),staff,"Incident");
        assertEquals(1,attemptEvents.findAll().stream().filter(e->e.getSessionId().equals(first.sessionId())&&e.getEventType().equals("exam.attempt.started")).count());
        attemptEvents.delete(event);
        assertTrue(service.backfillStartedEvents()>=1);assertEquals(0,service.backfillStartedEvents());
        assertEquals(event.getPayload(),attemptEvents.findById(event.getEventId()).orElseThrow().getPayload());
    }
    @Test void invalidatingAnActiveAttemptStopsItKeepsHistoryNeverRefundsAndNumbersStayStable() {
        var first=service.startExam(exam,user);service.saveAnswer(first.sessionId(),user,qid,option.toString());
        assertEquals(1,first.attemptNumber());
        var result=service.invalidate(first.sessionId(),staff,"  Technical incident confirmed  ");
        assertTrue(result.invalidated());assertEquals("CANCELLED",result.status());assertEquals("Technical incident confirmed",result.invalidationReason());assertEquals(staff,result.invalidatedBy());
        assertEquals(1,answers.findBySessionId(first.sessionId()).size()); // answers retained as history
        assertFalse(submissions.existsById(first.sessionId())); // an invalidated active attempt is never graded
        assertEquals(409,status(()->service.saveAnswer(first.sessionId(),user,qid,option.toString())));
        assertEquals(409,status(()->service.startExam(exam,user))); // no automatic refund: used attempt still counts
        authorizeWithLimit(1,2); // explicit per-Student grant from exam-service
        var second=service.startExam(exam,user);
        assertEquals(2,second.attemptNumber());assertNotEquals(first.sessionId(),second.sessionId());
        service.submitExam(second.sessionId(),user);
        authorizeWithLimit(1,3);
        assertEquals(3,service.startExam(exam,user).attemptNumber()); // numbers are monotonic, never reused
        var events=attemptEvents.findAll().stream().filter(e->e.getSessionId().equals(first.sessionId())&&!e.getEventType().equals("exam.attempt.started")).toList();
        assertEquals(1,events.size());assertEquals("exam.attempt.invalidated",events.get(0).getEventType());
        var payload=assertDoesNotThrow(()->json.readTree(events.get(0).getPayload()));
        assertEquals("Technical incident confirmed",payload.get("reason").asText());assertEquals(exam.toString(),payload.get("examId").asText());
        assertFalse(payload.toString().contains(option.toString())); // no answers in the event
    }

    @Test void submittedAttemptsKeepStatusGetFlaggedAndInvalidationIsIdempotentAndAuthorized() {
        var started=service.startExam(exam,user);service.submitExam(started.sessionId(),user);
        assertEquals(400,status(()->service.invalidate(started.sessionId(),staff," ")));
        assertEquals(400,status(()->service.invalidate(started.sessionId(),staff,null)));
        assertEquals(403,status(()->service.invalidate(started.sessionId(),other,"Not allowed")));
        assertFalse(sessions.findById(started.sessionId()).orElseThrow().isInvalidated());
        var done=service.invalidate(started.sessionId(),staff,"Cheating confirmed");
        assertEquals("SUBMITTED",done.status());assertTrue(done.invalidated());
        var again=service.invalidate(started.sessionId(),staff,"Another reason");
        assertEquals("Cheating confirmed",again.invalidationReason()); // first reason wins, no new event
        assertEquals(1,attemptEvents.findAll().stream().filter(e->e.getSessionId().equals(started.sessionId())&&e.getEventType().equals("exam.attempt.invalidated")).count());
        var detail=service.getSessionDetails(started.sessionId());
        assertNotNull(detail.invalidatedAt());assertEquals("Cheating confirmed",detail.invalidationReason());assertEquals("SUBMITTED",detail.status());
        assertEquals(404,status(()->service.invalidate(UUID.randomUUID(),staff,"Missing")));
    }

    @Test void studentSeesOnlyOwnAttemptsStaffNeedExamPermissionAndSummaryIsBatched() {
        authorizeWithLimit(3,3);
        var a=service.startExam(exam,user);service.submitExam(a.sessionId(),user);service.invalidate(a.sessionId(),staff,"Public reason");
        var b=service.startExam(exam,user);
        var o=service.startExam(exam,other);
        var mine=service.myAttempts(exam,user);
        assertEquals(2,mine.size());assertTrue(mine.stream().noneMatch(x->x.sessionId().equals(o.sessionId())));
        assertTrue(mine.get(0).invalidated());assertEquals("Public reason",mine.get(0).invalidationReason());assertFalse(mine.get(1).invalidated());
        assertEquals(1,service.myAttempts(exam,other).size());
        assertEquals(2,service.staffAttempts(exam,user,staff).size());
        assertEquals(403,status(()->service.staffAttempts(exam,user,other)));
        var summary=service.attemptSummary(exam,List.of(user,other,UUID.randomUUID()));
        var s=summary.get(0);
        assertEquals(2,s.used());assertEquals(1,s.valid());assertEquals(1,s.invalidated());assertTrue(s.active());assertEquals(2,s.latestAttemptNumber());assertEquals(b.sessionId(),s.latestSessionId());
        assertEquals(0,summary.get(2).used());assertNull(summary.get(2).latestSessionId());
        assertEquals(400,status(()->service.attemptSummary(exam,Collections.nCopies(101,UUID.randomUUID()))));
        assertEquals(400,status(()->service.attemptSummary(exam,List.of())));
        var monitor=service.monitor(exam,staff);
        assertTrue(monitor.stream().anyMatch(m->m.sessionId().equals(a.sessionId())&&m.invalidated()));
    }

    @Test void concurrentStartsOfOneStudentNeverCreateTwoActiveAttempts()throws Exception {
        authorizeWithLimit(2,2);
        var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try{
            List<Future<UUID>> futures=new ArrayList<>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{gate.await();return service.startExam(exam,user).sessionId();}));
            gate.countDown();
            Set<UUID> ids=new HashSet<>();for(var f:futures)ids.add(f.get());
            assertEquals(1,ids.size());
        }finally{pool.shutdownNow();}
        assertEquals(1,sessions.countByExamIdAndUserId(exam,user));
    }
}
