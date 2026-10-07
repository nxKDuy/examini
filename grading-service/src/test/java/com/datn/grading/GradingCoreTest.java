package com.datn.grading;

import com.datn.grading.client.*;
import com.datn.grading.dto.SubmissionDto;
import com.datn.grading.entity.*;
import com.datn.grading.repository.*;
import com.datn.grading.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/grading_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false","grading.events.outbox-initial-delay-ms=3600000","grading.events.outbox-poll-ms=3600000"})
@AutoConfigureMockMvc
class GradingCoreTest {
    @Autowired GradingService service;@Autowired ExamResultRepository results;@Autowired ResultPublicationRepository publications;
    @Autowired GradingAuditRepository audits;@Autowired GradingOutboxRepository outbox;@Autowired GradingOutboxRelay relay;@Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockBean ExamClient exams;@MockBean ExamSessionClient sessions;@MockBean GeminiClient ai;@MockBean RabbitTemplate rabbit;
    UUID session,exam,user,teacher;

    @BeforeEach void setup(){
        outbox.deleteAll();audits.deleteAll();publications.deleteAll();results.deleteAll();
        session=UUID.randomUUID();exam=UUID.randomUUID();user=UUID.randomUUID();teacher=UUID.randomUUID();
        when(exams.resultPolicy(eq(exam),eq("test-internal"))).thenReturn(policy(true,true,true,true,true,false,"CLOSED"));
        when(exams.permission(eq(exam),any(),eq(true),eq("test-internal"))).thenReturn("OWNER");
    }
    ExamClient.Question question(String type,String expected,int position){return new ExamClient.Question(UUID.randomUUID(),3,type,"Question "+position,List.of(new ExamClient.Option(UUID.randomUUID(),"Yes",0,true),new ExamClient.Option(UUID.randomUUID(),"No",1,false)),new BigDecimal("2.5"),expected,position);}
    ExamClient.ResultPolicy policy(boolean auto,boolean score,boolean breakdown,boolean feedback,boolean correct,boolean explanation,String status){return new ExamClient.ResultPolicy(status,new ExamClient.Policy(auto,score,breakdown,feedback,correct,explanation));}
    void submitted(List<ExamClient.Question> keys,List<ExamSessionClient.AnswerDto> answers){
        var now=LocalDateTime.now();when(sessions.get(session,"test-internal")).thenReturn(new ExamSessionClient.SessionDetailDto(session,exam,user,"SUBMITTED","Exam",2,now.minusMinutes(5),now,answers));
        when(exams.key(exam,"test-internal")).thenReturn(keys);service.processSubmittedExam(session.toString());
    }
    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}

    @Test void objectiveScoringAndNormalization(){
        for(String type:List.of("MULTIPLE_CHOICE","TRUE_FALSE")){var q=question(type,null,0);assertEquals(2.5f,ObjectiveScoring.score(q,q.options().get(0).id().toString()));assertEquals(0,ObjectiveScoring.score(q,q.options().get(1).id().toString()));assertEquals(0,ObjectiveScoring.score(q,null));}
        var q=question("SHORT_ANSWER","Café au lait",0);assertEquals(2.5f,ObjectiveScoring.score(q,"  Cafe\u0301\u00a0au\t lait  "));assertEquals(0,ObjectiveScoring.score(q,"café au lait"));assertEquals(0,ObjectiveScoring.score(q,"Cafe au lait"));
    }

    @Test void duplicateEventsEssayFallbackAndOutboxAreIdempotent()throws Exception{
        var objective=question("MULTIPLE_CHOICE",null,0);var essay=question("ESSAY",null,1);var now=LocalDateTime.now();
        when(sessions.get(session,"test-internal")).thenReturn(new ExamSessionClient.SessionDetailDto(session,exam,user,"SUBMITTED","Exam",1,now.minusMinutes(5),now,List.of(new ExamSessionClient.AnswerDto(objective.questionId(),objective.options().get(0).id().toString()),new ExamSessionClient.AnswerDto(essay.questionId(),"Essay"))));
        when(exams.key(exam,"test-internal")).thenReturn(List.of(objective,essay));when(ai.gradeEssay(anyString(),anyString())).thenThrow(new IllegalStateException("Unavailable"));
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.processSubmittedExam(session.toString()));var b=pool.submit(()->service.processSubmittedExam(session.toString()));a.get();b.get();}finally{pool.shutdownNow();}
        var result=results.findBySessionId(session).orElseThrow();assertEquals(ExamResult.Status.NEEDS_REVIEW,result.getStatus());assertEquals(2.5f,result.getTotalScore());assertEquals(5,result.getMaxScore());assertNull(result.getGradedAt());
        assertEquals(1,results.findAll().size());assertEquals(Set.of("grading.result.recorded.v1","grading.review.required"),outbox.findAll().stream().map(GradingOutboxEvent::getRoutingKey).collect(java.util.stream.Collectors.toSet()));
        assertTrue(outbox.findAll().stream().filter(e->e.getRoutingKey().startsWith("grading.review")).noneMatch(e->e.getPayload().contains("score")||e.getPayload().contains("answer")));
        assertTrue(outbox.findAll().stream().filter(e->e.getRoutingKey().equals("grading.result.recorded.v1")).noneMatch(e->e.getPayload().contains("answerContent")||e.getPayload().contains("expectedAnswer")));
        var recorded=json.readTree(outbox.findAll().stream().filter(e->e.getRoutingKey().equals("grading.result.recorded.v1")).findFirst().orElseThrow().getPayload());
        assertEquals(1,recorded.path("attemptNumber").asInt());assertTrue(recorded.path("questions").findValuesAsText("questionVersion").contains("3"));assertTrue(recorded.path("questions").findValuesAsText("position").contains("1"));assertTrue(recorded.path("questions").findValuesAsText("pointValue").contains("2.5"));
    }

    @Test void manualDraftFinalizeRegradeHistoryAndVisibilityPolicy()throws Exception{
        var objective=question("MULTIPLE_CHOICE",null,0);var essay=question("ESSAY",null,1);when(ai.gradeEssay(anyString(),anyString())).thenThrow(new IllegalStateException("Unavailable"));
        submitted(List.of(objective,essay),List.of(new ExamSessionClient.AnswerDto(objective.questionId(),objective.options().get(0).id().toString()),new ExamSessionClient.AnswerDto(essay.questionId(),"Own essay")));
        UUID resultId=results.findBySessionId(session).orElseThrow().getId();
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.finalizeResult(resultId,1,teacher)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.saveDraft(resultId,new SubmissionDto.SaveDraftRequest(1,null,List.of(new SubmissionDto.ItemGrade(essay.questionId(),3f,null))),teacher)).getStatusCode().value());
        var draft=service.saveDraft(resultId,new SubmissionDto.SaveDraftRequest(1,"Overall",List.of(new SubmissionDto.ItemGrade(objective.questionId(),1.5f,"Override objective"),new SubmissionDto.ItemGrade(essay.questionId(),2f,"Essay feedback"))),teacher);
        assertEquals(2,draft.resultRevision());assertEquals("PENDING",draft.status());assertEquals(3.5f,draft.score());assertEquals(2.5f,draft.items().get(0).autoScore());assertEquals(1.5f,draft.items().get(0).manualScore());
        var finalized=service.finalizeResult(resultId,2,teacher);assertEquals(3,finalized.resultRevision());assertEquals("GRADED",finalized.status());assertNotNull(finalized.gradedAt());assertEquals(3L,finalized.releasedRevision());
        var first=service.student(session,user);assertEquals(3,first.resultRevision());assertEquals(3.5f,first.score());assertEquals(2,first.questions().size());assertEquals(1,first.history().size());assertThrows(ResponseStatusException.class,()->service.student(session,UUID.randomUUID()));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.beginRegrade(resultId,new SubmissionDto.RegradeRequest(3," ","private"),teacher)).getStatusCode().value());
        var regrade=service.beginRegrade(resultId,new SubmissionDto.RegradeRequest(3,"Điều chỉnh đáp án","private audit only"),teacher);assertEquals(4,regrade.resultRevision());assertEquals(3L,service.student(session,user).resultRevision());
        service.saveDraft(resultId,new SubmissionDto.SaveDraftRequest(4,"Updated",List.of(new SubmissionDto.ItemGrade(objective.questionId(),0f,"Changed"))),teacher);
        var refinalized=service.finalizeResult(resultId,5,teacher);assertEquals(6,refinalized.resultRevision());assertEquals(6L,refinalized.releasedRevision());
        var latest=service.student(session,user);assertEquals(2,latest.history().size());assertEquals("Điều chỉnh đáp án",latest.history().get(1).publicReason());assertFalse(json.writeValueAsString(latest).contains("private audit only"));
        assertTrue(audits.findByResultIdOrderByCreatedAtAsc(resultId).stream().anyMatch(a->"private audit only".equals(a.getPrivateNote())));
        assertTrue(outbox.findAll().stream().anyMatch(e->e.getRoutingKey().equals("grading.result.regraded")));
        assertTrue(outbox.findAll().stream().filter(e->e.getExchangeName().equals("examini.events")).noneMatch(e->e.getPayload().contains("score")||e.getPayload().contains("answer")||e.getPayload().contains("private audit")));
        var released=json.readTree(outbox.findAll().stream().filter(e->e.getRoutingKey().equals("grading.result.released")).findFirst().orElseThrow().getPayload()).path("payload");assertEquals(resultId.toString(),released.path("resultId").asText());assertEquals(3,released.path("resultRevision").asLong());

        when(exams.resultPolicy(eq(exam),eq("test-internal"))).thenReturn(policy(true,false,false,false,true,false,"OPEN"));
        String hidden=mvc.perform(get("/grades/session/"+session).header("Authorization",token(user,"STUDENT"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(hidden.contains("\"score\""));assertFalse(hidden.contains("awardedPoints"));assertFalse(hidden.contains("correctAnswers"));assertTrue(hidden.contains("Own essay"));
        when(exams.resultPolicy(eq(exam),eq("test-internal"))).thenReturn(policy(true,true,true,true,true,false,"CLOSED"));
        assertNotNull(service.student(session,user).questions().get(0).correctAnswers());
    }

    @Test void explicitReleaseAndRoleIsolation()throws Exception{
        var objective=question("TRUE_FALSE",null,0);when(exams.resultPolicy(eq(exam),eq("test-internal"))).thenReturn(policy(false,true,true,true,false,false,"OPEN"));
        submitted(List.of(objective),List.of(new ExamSessionClient.AnswerDto(objective.questionId(),objective.options().get(0).id().toString())));
        var result=results.findBySessionId(session).orElseThrow();assertEquals("GRADING",service.student(session,user).status());assertNull(result.getReleasedRevision());
        UUID owner=UUID.randomUUID(),manager=UUID.randomUUID(),proctor=UUID.randomUUID(),unrelated=UUID.randomUUID();
        when(exams.permission(eq(exam),any(),eq(true),eq("test-internal"))).thenAnswer(call->{UUID actor=call.getArgument(1);if(actor.equals(owner))return "OWNER";if(actor.equals(manager))return "MANAGER";throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Denied");});
        assertNotNull(service.detail(result.getId(),owner));assertNotNull(service.detail(result.getId(),manager));assertThrows(ResponseStatusException.class,()->service.detail(result.getId(),proctor));assertThrows(ResponseStatusException.class,()->service.detail(result.getId(),unrelated));
        mvc.perform(get("/submissions/"+result.getId()).header("Authorization",token(owner,"TEACHER"))).andExpect(status().isOk());
        mvc.perform(get("/submissions/"+result.getId()).header("Authorization",token(manager,"TEACHER"))).andExpect(status().isOk());
        mvc.perform(get("/submissions/"+result.getId()).header("Authorization",token(proctor,"TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/submissions/"+result.getId()).header("Authorization",token(unrelated,"ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/submissions/"+result.getId()).header("Authorization",token(user,"STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/grades/session/"+session).header("Authorization",token(UUID.randomUUID(),"STUDENT"))).andExpect(status().isForbidden());
        var released=service.release(result.getId(),1,owner);assertEquals(2,released.resultRevision());assertEquals(2L,released.releasedRevision());assertEquals("GRADED",service.student(session,user).status());
    }

    @Test void concurrentDraftUsesOptimisticRevisionAndReturnsOneConflict()throws Exception{
        var essay=question("ESSAY",null,0);when(ai.gradeEssay(anyString(),anyString())).thenThrow(new IllegalStateException("Unavailable"));submitted(List.of(essay),List.of(new ExamSessionClient.AnswerDto(essay.questionId(),"Essay")));
        UUID resultId=results.findBySessionId(session).orElseThrow().getId();var request=new SubmissionDto.SaveDraftRequest(1,null,List.of(new SubmissionDto.ItemGrade(essay.questionId(),2f,"Reviewed")));
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);List<Future<Integer>> calls=new ArrayList<>();
        try{for(int i=0;i<2;i++)calls.add(pool.submit(()->{gate.await();try{service.saveDraft(resultId,request,teacher);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));gate.countDown();assertEquals(List.of(200,409),calls.stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).sorted().toList());}finally{pool.shutdownNow();}
    }

    @Test void outboxRetryKeepsStableEventId(){
        var objective=question("TRUE_FALSE",null,0);submitted(List.of(objective),List.of());
        var target=outbox.findAll().stream().filter(e->e.getRoutingKey().equals("grading.result.recorded.v1")).findFirst().orElseThrow();UUID eventId=target.getEventId();
        outbox.findAll().stream().filter(e->!e.getEventId().equals(eventId)).forEach(outbox::delete);outbox.flush();
        doThrow(new RuntimeException("broker unavailable")).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));relay.relay();
        var pending=outbox.findById(eventId).orElseThrow();assertFalse(pending.isDelivered());assertEquals(1,pending.getAttemptCount());
        reset(rabbit);doAnswer(call->{CorrelationData correlation=call.getArgument(3);correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));relay.relay();
        var delivered=outbox.findById(eventId).orElseThrow();assertTrue(delivered.isDelivered());assertEquals(eventId,delivered.getEventId());assertEquals(2,delivered.getAttemptCount());
    }
}
