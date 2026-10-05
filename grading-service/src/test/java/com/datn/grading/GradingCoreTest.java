package com.datn.grading;
import com.datn.grading.client.*;
import com.datn.grading.entity.ExamResult;
import com.datn.grading.repository.ExamResultRepository;
import com.datn.grading.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/grading_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false"})
class GradingCoreTest {
    @Autowired GradingService service;@Autowired ExamResultRepository results;
    @MockBean ExamClient exams;@MockBean ExamSessionClient sessions;@MockBean GeminiClient ai;
    ExamClient.Question question(String type,String expected){return new ExamClient.Question(UUID.randomUUID(),1,type,"Question",List.of(new ExamClient.Option(UUID.randomUUID(),"Yes",0,true),new ExamClient.Option(UUID.randomUUID(),"No",1,false)),new BigDecimal("2.5"),expected,0);}
    @Test void objectiveScoringAndNormalization(){
        for(String type:List.of("MULTIPLE_CHOICE","TRUE_FALSE")){var q=question(type,null);assertEquals(2.5f,ObjectiveScoring.score(q,q.options().get(0).id().toString()));assertEquals(0,ObjectiveScoring.score(q,q.options().get(1).id().toString()));assertEquals(0,ObjectiveScoring.score(q,null));}
        var q=question("SHORT_ANSWER","Café au lait");assertEquals(2.5f,ObjectiveScoring.score(q,"  Cafe\u0301\u00a0au\t lait  "));assertEquals(0,ObjectiveScoring.score(q,"café au lait"));assertEquals(0,ObjectiveScoring.score(q,"Cafe au lait"));
    }
    @Test void duplicateEventsReviewFallbackAndOwnedResult()throws Exception {
        UUID id=UUID.randomUUID(),exam=UUID.randomUUID(),user=UUID.randomUUID();var objective=question("MULTIPLE_CHOICE",null);var essay=question("ESSAY",null);
        when(sessions.get(id,"test-internal")).thenReturn(new ExamSessionClient.SessionDetailDto(id,exam,user,"SUBMITTED","Exam",LocalDateTime.now().minusMinutes(5),LocalDateTime.now(),List.of(new ExamSessionClient.AnswerDto(objective.questionId(),objective.options().get(0).id().toString()),new ExamSessionClient.AnswerDto(essay.questionId(),"Essay"))));
        when(exams.key(exam,"test-internal")).thenReturn(List.of(objective,essay));when(ai.gradeEssay(anyString(),anyString())).thenThrow(new IllegalStateException("Unavailable"));
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.processSubmittedExam(id.toString()));var b=pool.submit(()->service.processSubmittedExam(id.toString()));a.get();b.get();}finally{pool.shutdownNow();}
        var r=results.findBySessionId(id).orElseThrow();assertEquals(ExamResult.Status.NEEDS_REVIEW,r.getStatus());assertEquals(2.5f,r.getTotalScore());assertEquals(5,r.getMaxScore());assertNull(service.student(id,user).score());assertThrows(Exception.class,()->service.student(id,UUID.randomUUID()));
        verify(exams,times(1)).key(exam,"test-internal");assertEquals(1,results.findAll().stream().filter(v->v.getSessionId().equals(id)).count());
    }
}
