package com.datn.examsession;
import com.datn.examsession.client.ExamClient;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import com.datn.examsession.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/session_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false"})
class SessionCoreTest {
    @Autowired SessionService service;@Autowired SessionRepository sessions;@Autowired SessionAnswerRepository answers;@Autowired SubmissionEventRepository events;@Autowired PlatformTransactionManager transactions;
    @MockBean ExamClient exams;@MockBean SessionMaintenance maintenance;
    UUID exam,user,qid,option;
    @BeforeEach void setup(){exam=UUID.randomUUID();user=UUID.randomUUID();qid=UUID.randomUUID();option=UUID.randomUUID();when(exams.authorize(eq(exam),any(),eq("test-internal"))).thenReturn(new ExamClient.ExamDto(exam,"Exam",10,1,1,null,false,false));when(exams.safe(exam,"test-internal")).thenReturn(List.of(new ExamClient.Question(qid,1,"MULTIPLE_CHOICE","Original",List.of(new ExamClient.Option(option,"Choice",0)),BigDecimal.TEN,0)));stubAdmissions();}
    private void stubAdmissions(){
        when(exams.reserveStart(any(),any(),eq("test-internal"))).thenAnswer(call->new ExamClient.StartReservation(call.getArgument(0),UUID.randomUUID(),1));
        when(exams.claimStart(any(),any(),any(),eq("test-internal"))).thenAnswer(call->{UUID admission=call.getArgument(1);Instant now=Instant.now();return new ExamClient.ScheduleAdmission(admission,1,now,now.plusSeconds(600));});
    }
    @Test void practiceAttemptsCheckAnswersOneByOneAndLockThem(){
        UUID practice=UUID.randomUUID(),gapQ=UUID.randomUUID(),gap=UUID.randomUUID(),wrong=UUID.randomUUID();
        when(exams.authorize(eq(practice),any(),anyString())).thenReturn(new ExamClient.ExamDto(practice,"Luyện tập",10,1,1,null,false,false,null,"PRACTICE"));
        when(exams.safe(eq(practice),anyString())).thenReturn(List.of(new ExamClient.Question(qid,1,"MULTIPLE_CHOICE","Thủ đô?",List.of(new ExamClient.Option(option,"Hà Nội",0),new ExamClient.Option(wrong,"Huế",1)),BigDecimal.TEN,0),
                new ExamClient.Question(gapQ,1,"FILL_BLANK","Năm ___",List.of(new ExamClient.Option(gap,"",0)),BigDecimal.ONE,1)));
        when(exams.keys(eq(practice),anyString())).thenReturn(List.of(
                new ExamClient.KeyQuestion(qid,"MULTIPLE_CHOICE","Thủ đô?",List.of(new ExamClient.KeyOption(option,"Hà Nội",0,true),new ExamClient.KeyOption(wrong,"Huế",1,false)),null,"Từ 1976."),
                new ExamClient.KeyQuestion(gapQ,"FILL_BLANK","Năm ___",List.of(new ExamClient.KeyOption(gap,"1945 | năm 1945",0,true)),null,null)));
        var s=service.startExam(practice,user);assertTrue(s.practice());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.checkAnswer(s.sessionId(),user,qid)).getStatusCode().value());
        service.saveAnswer(s.sessionId(),user,qid,wrong.toString());
        var verdict=service.checkAnswer(s.sessionId(),user,qid);
        assertFalse(verdict.correct());assertEquals(0,verdict.share());assertEquals(List.of(option),verdict.correctOptionIds());assertEquals(List.of("Hà Nội"),verdict.correctAnswers());assertEquals("Từ 1976.",verdict.explanation());
        // Locked: no second try after seeing the key; checking again returns the same verdict.
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.saveAnswer(s.sessionId(),user,qid,option.toString())).getStatusCode().value());
        assertFalse(service.checkAnswer(s.sessionId(),user,qid).correct());
        service.saveAnswer(s.sessionId(),user,gapQ,"{\""+gap+"\":\" Năm  1945\"}");
        var filled=service.checkAnswer(s.sessionId(),user,gapQ);assertTrue(filled.correct());assertEquals(Map.of(gap.toString(),true),filled.parts());
        var resumed=service.get(s.sessionId(),user);assertEquals(2,resumed.checks().size());assertFalse(resumed.checks().get(qid).correct());
        // Official exams never reveal keys during the attempt.
        var official=service.startExam(exam,user);assertFalse(official.practice());
        service.saveAnswer(official.sessionId(),user,qid,option.toString());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.checkAnswer(official.sessionId(),user,qid)).getStatusCode().value());
    }
    @Test void startResumeAnswerSubmitClosedAndAttempts(){
        var started=service.startExam(exam,user);assertEquals(started.sessionId(),service.startExam(exam,user).sessionId());
        service.saveAnswer(started.sessionId(),user,qid,option.toString());assertEquals(option.toString(),service.get(started.sessionId(),user).answers().get(qid));
        when(exams.authorize(eq(exam),any(),anyString())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"CLOSED"));
        assertEquals(started.sessionId(),service.startExam(exam,user).sessionId());
        assertThrows(Exception.class,()->service.startExam(exam,UUID.randomUUID()));
        service.submitExam(started.sessionId(),user);service.submitExam(started.sessionId(),user);assertEquals(1,events.findAll().stream().filter(e->e.getSessionId().equals(started.sessionId())).count());
        assertThrows(Exception.class,()->service.saveAnswer(started.sessionId(),user,qid,""));
        when(exams.authorize(eq(exam),any(),anyString())).thenReturn(new ExamClient.ExamDto(exam,"Exam",10,1,1,null,false,false));
        assertThrows(Exception.class,()->service.startExam(exam,user));
        var another=service.startExam(exam,UUID.randomUUID());assertNotEquals(started.sessionId(),another.sessionId());
    }
    @Test void timeoutIsPersistedAndAnswersCannotEscapeSnapshot(){
        var started=service.startExam(exam,user);assertThrows(Exception.class,()->service.saveAnswer(started.sessionId(),user,UUID.randomUUID(),"invalid"));
        new TransactionTemplate(transactions).executeWithoutResult(t->{var s=sessions.findById(started.sessionId()).orElseThrow();s.setExpiresAt(Instant.now().minusSeconds(1));});
        assertThrows(Exception.class,()->service.saveAnswer(started.sessionId(),user,qid,option.toString()));
        assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(started.sessionId()).orElseThrow().getStatus());assertTrue(events.existsById(started.sessionId()));
        service.autoSubmitExam(started.sessionId());assertEquals(0,answers.findBySessionId(started.sessionId()).size());
    }
    @Test void concurrentSubmitAndTimeoutProducesOneEvent()throws Exception {
        var started=service.startExam(exam,user);
        new TransactionTemplate(transactions).executeWithoutResult(t->sessions.findById(started.sessionId()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1)));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var manual=pool.submit(()->{gate.await();service.submitExam(started.sessionId(),user);return true;});var timeout=pool.submit(()->{gate.await();service.autoSubmitExam(started.sessionId());return true;});gate.countDown();assertTrue(manual.get());assertTrue(timeout.get());}
        finally{pool.shutdownNow();}
        assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(started.sessionId()).orElseThrow().getStatus());
        assertEquals(1,events.findAll().stream().filter(e->e.getSessionId().equals(started.sessionId())).count());
    }
    @Test void atomicCapacityAndMultipleAttempts()throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var a=pool.submit(()->{gate.await();try{return service.startExam(exam,user).sessionId();}catch(Exception e){return null;}});var b=pool.submit(()->{gate.await();try{return service.startExam(exam,UUID.randomUUID()).sessionId();}catch(Exception e){return null;}});gate.countDown();UUID x=a.get(),y=b.get();assertEquals(1,(x!=null?1:0)+(y!=null?1:0));assertEquals(1,sessions.countByExamIdAndStatus(exam,ExamSession.Status.IN_PROGRESS));}
        finally{pool.shutdownNow();}
        UUID multi=UUID.randomUUID();when(exams.authorize(eq(multi),any(),anyString())).thenReturn(new ExamClient.ExamDto(multi,"Multiple",10,1,3,null,false,false));var fixture=exams.safe(exam,"test-internal");when(exams.safe(eq(multi),anyString())).thenReturn(fixture);
        var first=service.startExam(multi,user);service.submitExam(first.sessionId(),user);var second=service.startExam(multi,user);assertNotEquals(first.sessionId(),second.sessionId());assertEquals(2,second.attemptNumber());assertEquals(2,service.getSessionDetails(second.sessionId()).attemptNumber());
    }
    @Test void deadlinesStayFrozenAndNewAttemptUsesNewScheduleRevision() {
        UUID multi=UUID.randomUUID();
        when(exams.authorize(eq(multi),any(),anyString())).thenReturn(new ExamClient.ExamDto(multi,"Revised",30,2,2,null,false,false));
        var frozenQuestions=exams.safe(exam,"test-internal");
        when(exams.safe(eq(multi),anyString())).thenReturn(frozenQuestions);
        UUID firstAdmission=UUID.randomUUID(),secondAdmission=UUID.randomUUID();
        Instant firstStart=Instant.parse("2030-01-01T10:00:00Z"),firstDeadline=firstStart.plusSeconds(1200);
        Instant secondStart=Instant.parse("2030-01-01T11:00:00Z"),secondDeadline=secondStart.plusSeconds(1800);
        when(exams.reserveStart(eq(multi),eq(user),eq("test-internal")))
                .thenReturn(new ExamClient.StartReservation(multi,firstAdmission,1),new ExamClient.StartReservation(multi,secondAdmission,2));
        when(exams.claimStart(eq(multi),eq(firstAdmission),eq(user),eq("test-internal"))).thenReturn(new ExamClient.ScheduleAdmission(firstAdmission,1,firstStart,firstDeadline));
        when(exams.claimStart(eq(multi),eq(secondAdmission),eq(user),eq("test-internal"))).thenReturn(new ExamClient.ScheduleAdmission(secondAdmission,2,secondStart,secondDeadline));

        var first=service.startExam(multi,user);assertEquals(firstDeadline,first.expiresAt());
        service.submitExam(first.sessionId(),user);
        var second=service.startExam(multi,user);
        assertEquals(secondDeadline,second.expiresAt());assertEquals(2,sessions.findById(second.sessionId()).orElseThrow().getScheduleRevision());
        assertEquals(firstDeadline,sessions.findById(first.sessionId()).orElseThrow().getExpiresAt());
        assertEquals(1,sessions.findById(first.sessionId()).orElseThrow().getScheduleRevision());
    }
    @Test void availabilityLazilyExpiresSessionAndReportsAuthoritativeTerminalState() {
        var started=service.startExam(exam,user);
        new TransactionTemplate(transactions).executeWithoutResult(t->sessions.findById(started.sessionId()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1)));
        var availability=service.availability(exam,user);
        assertFalse(availability.resumable());assertEquals("AUTO_SUBMITTED",availability.latestSessionStatus());
        assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(started.sessionId()).orElseThrow().getStatus());
        assertTrue(events.existsById(started.sessionId()));
    }
    @Test void failedStartReleasesReservedAdmission() {
        UUID admission=UUID.randomUUID();
        when(exams.reserveStart(eq(exam),eq(user),eq("test-internal"))).thenReturn(new ExamClient.StartReservation(exam,admission,1));
        when(exams.safe(eq(exam),eq("test-internal"))).thenThrow(new IllegalStateException("provider unavailable"));
        assertThrows(IllegalStateException.class,()->service.startExam(exam,user));
        verify(exams).releaseStart(exam,admission,"test-internal");
        assertTrue(sessions.findByExamId(exam).isEmpty());
    }
    @Test void recipientSubmittedTracksCurrentAttemptAndNormalizesExpiry() {
        UUID multi=UUID.randomUUID();
        when(exams.authorize(eq(multi),any(),anyString())).thenReturn(new ExamClient.ExamDto(multi,"Recipient",10,2,3,null,false,false));
        var frozenQuestions=exams.safe(exam,"test-internal");
        when(exams.safe(eq(multi),anyString())).thenReturn(frozenQuestions);
        var first=service.startExam(multi,user);service.submitExam(first.sessionId(),user);
        var second=service.startExam(multi,user);
        var whileActive=service.recipientProgress(multi,List.of(user)).get(0);
        assertTrue(whileActive.started());assertFalse(whileActive.submitted());
        new TransactionTemplate(transactions).executeWithoutResult(t->sessions.findById(second.sessionId()).orElseThrow().setExpiresAt(Instant.now().minusSeconds(1)));
        var afterExpiry=service.recipientProgress(multi,List.of(user)).get(0);
        assertTrue(afterExpiry.submitted());
        assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(second.sessionId()).orElseThrow().getStatus());
    }
}
