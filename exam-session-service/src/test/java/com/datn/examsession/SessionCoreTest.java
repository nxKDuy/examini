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
    @BeforeEach void setup(){exam=UUID.randomUUID();user=UUID.randomUUID();qid=UUID.randomUUID();option=UUID.randomUUID();when(exams.authorize(eq(exam),any(),eq("test-internal"))).thenReturn(new ExamClient.ExamDto(exam,"Exam",10,1,1,null,false,false));when(exams.safe(exam,"test-internal")).thenReturn(List.of(new ExamClient.Question(qid,1,"MULTIPLE_CHOICE","Original",List.of(new ExamClient.Option(option,"Choice",0)),BigDecimal.TEN,0)));}
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
        var first=service.startExam(multi,user);service.submitExam(first.sessionId(),user);var second=service.startExam(multi,user);assertNotEquals(first.sessionId(),second.sessionId());assertEquals(2,second.attemptNumber());
    }
}
