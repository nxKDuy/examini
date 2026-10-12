package com.datn.examsession;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.dto.IntegrityDto.*;
import com.datn.examsession.dto.IntegrityPolicy;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import com.datn.examsession.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/session_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false","exam.integrity.events-per-minute=4","exam.integrity.max-events-per-session=10","exam.integrity.leave-coalesce-ms=300"})
@AutoConfigureMockMvc
class IntegrityTest {
    @Autowired SessionService core; @Autowired IntegrityService integrity;
    @Autowired SessionRepository sessions; @Autowired IntegrityEventRepository events;
    @Autowired SubmissionEventRepository submissions; @Autowired SessionIntegrityAuditRepository integrityAudits;
    @Autowired ExamIntegrityLockRepository integrityLocks; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean ExamClient exams; @MockBean SessionMaintenance maintenance;
    UUID exam,user,owner,manager,proctor,other,session,qid;
    IntegrityPolicy policy=new IntegrityPolicy(true,true,true,true,true,true,IntegrityPolicy.WarningPolicy.WARN_ONLY,3);
    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("systemPrivilege","ADMIN".equals(role)?"ADMIN":"NONE").claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}
    @BeforeEach void setup(){
        exam=UUID.randomUUID();user=UUID.randomUUID();owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();other=UUID.randomUUID();qid=UUID.randomUUID();
        when(exams.authorize(eq(exam),any(),eq("test-internal"))).thenReturn(new ExamClient.ExamDto(exam,"Integrity",10,5,3,null,false,false,policy));
        when(exams.safe(exam,"test-internal")).thenReturn(List.of(new ExamClient.Question(qid,1,"SHORT_ANSWER","Question",List.of(),BigDecimal.TEN,0)));
        when(exams.reserveStart(any(),any(),eq("test-internal"))).thenAnswer(call->new ExamClient.StartReservation(call.getArgument(0),UUID.randomUUID(),1));
        when(exams.claimStart(any(),any(),any(),eq("test-internal"))).thenAnswer(call->{UUID admission=call.getArgument(1);Instant now=Instant.now();return new ExamClient.ScheduleAdmission(admission,1,now,now.plusSeconds(600));});
        when(exams.permission(eq(exam),any(),eq(false),eq("test-internal"))).thenAnswer(call->{UUID actor=call.getArgument(1);if(actor.equals(owner))return "OWNER";if(actor.equals(manager))return "MANAGER";if(actor.equals(proctor))return "PROCTOR";throw new ResponseStatusException(HttpStatus.FORBIDDEN,"No exam access");});
        session=core.startExam(exam,user).sessionId();
    }
    Ingest event(EventType type){return new Ingest(UUID.randomUUID(),type,Instant.now(),null);}
    void postEvent(UUID actor,String role,Ingest input,int statusCode)throws Exception {
        mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(actor,role)).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().is(statusCode));
    }
    @Test void authenticatedIngestionBindingPrivacyAndState()throws Exception {
        Ingest input=event(EventType.TAB_SWITCH);postEvent(user,"STUDENT",input,200);
        var stored=events.findBySessionIdAndClientEventId(session,input.clientEventId()).orElseThrow();
        assertEquals(exam,stored.getExamId());assertEquals(user,stored.getUserId());assertEquals(session,stored.getSessionId());assertEquals("BROWSER",stored.getSource());assertNotNull(stored.getReceivedAt());
        postEvent(other,"STUDENT",event(EventType.WINDOW_BLUR),403);postEvent(owner,"TEACHER",event(EventType.COPY),403);
        mvc.perform(post("/sessions/"+session+"/integrity/events").contentType("application/json").content(json.writeValueAsString(event(EventType.COPY)))).andExpect(status().isUnauthorized());
        for(String type:List.of("WEBCAM","NO_FACE","UNKNOWN"))mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(user,"STUDENT")).contentType("application/json").content("{\"clientEventId\":\""+UUID.randomUUID()+"\",\"type\":\""+type+"\"}")).andExpect(status().isBadRequest());
        for(String field:List.of("clipboard","password","jwt","pageContent","fingerprint"))mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(user,"STUDENT")).contentType("application/json").content("{\"clientEventId\":\""+UUID.randomUUID()+"\",\"type\":\"COPY\",\"metadata\":{\""+field+"\":\"private\"}}")).andExpect(status().isBadRequest());
        postEvent(user,"STUDENT",new Ingest(UUID.randomUUID(),EventType.COPY,null,new Metadata(1L,null,null,null,null)),400);
        postEvent(user,"STUDENT",new Ingest(UUID.randomUUID(),EventType.NETWORK_RECONNECT,null,new Metadata(-1L,null,null,null,NetworkState.ONLINE)),400);
        core.submitExam(session,user);
        var late=integrity.ingest(session,user,"STUDENT",event(EventType.TAB_SWITCH)); // recorded for audit, never counted
        assertFalse(late.counted());assertEquals(NotCountedReason.STALE,late.notCountedReason());assertEquals(1,late.warningCount());assertFalse(late.autoSubmitted());
        postEvent(user,"STUDENT",input,200);assertEquals(2,events.countBySessionId(session));
    }
    @Test void reloadAndResumeUseServerReceiptAndFrozenMonitoringPolicy() {
        Instant clientTime=Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var reload=integrity.ingest(session,user,"STUDENT",new Ingest(UUID.randomUUID(),EventType.PAGE_RELOAD,clientTime,null));
        var resume=integrity.ingest(session,user,"STUDENT",new Ingest(UUID.randomUUID(),EventType.SESSION_RESUME,null,null));
        assertEquals(clientTime,reload.event().occurredAt());assertNotNull(reload.event().receivedAt());
        assertTrue(reload.event().receivedAt().isAfter(clientTime));assertEquals("BROWSER",resume.event().source());
        assertFalse(reload.warningRecommended());assertFalse(resume.warningRecommended());
        assertThrows(ResponseStatusException.class,()->integrity.ingest(session,user,"STUDENT",
                new Ingest(UUID.randomUUID(),EventType.PAGE_RELOAD,null,new Metadata(1L,null,null,null,null))));
    }
    @Test void teacherPersonaTakingExamUsesOwnedSessionWithoutGlobalReview()throws Exception {
        postEvent(user,"TEACHER",event(EventType.COPY),200);
        postEvent(user,"ADMIN",event(EventType.WINDOW_BLUR),200);
        mvc.perform(get("/sessions/"+session+"/integrity/summary").header("Authorization",token(user,"TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/"+session+"/integrity/summary").header("Authorization",token(user,"ADMIN"))).andExpect(status().isForbidden());
        core.saveAnswer(session,user,qid,"4");core.submitExam(session,user);
        assertEquals(2,events.countBySessionId(session));
    }
    @Test void duplicateRaceCollisionAndAttemptScope()throws Exception {
        Ingest input=event(EventType.COPY);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {var a=pool.submit(()->{gate.await();return integrity.ingest(session,user,"STUDENT",input);});var b=pool.submit(()->{gate.await();return integrity.ingest(session,user,"STUDENT",input);});gate.countDown();var x=a.get();var y=b.get();assertEquals(x.event().id(),y.event().id());assertNotEquals(x.duplicate(),y.duplicate());}finally{pool.shutdownNow();}
        postEvent(user,"STUDENT",new Ingest(input.clientEventId(),EventType.PASTE,input.occurredAt(),null),409);
        core.submitExam(session,user);UUID next=core.startExam(exam,user).sessionId();
        var result=integrity.ingest(next,user,"STUDENT",input);assertNotEquals(session,result.event().sessionId());assertEquals(1,events.countBySessionId(next));
    }
    @Test void summaryCountsAuthorizationAndPagination()throws Exception {
        postEvent(user,"STUDENT",event(EventType.TAB_SWITCH),200);postEvent(user,"STUDENT",event(EventType.TAB_SWITCH),200);
        postEvent(user,"STUDENT",event(EventType.EXIT_FULLSCREEN),200);
        var network=new Ingest(UUID.randomUUID(),EventType.NETWORK_RECONNECT,Instant.now().minusSeconds(60),new Metadata(60000L,null,null,null,NetworkState.ONLINE));postEvent(user,"STUDENT",network,200);
        for(UUID actor:List.of(owner,manager,proctor)) {
            mvc.perform(get("/sessions/"+session+"/integrity/summary").header("Authorization",token(actor,"TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalEvents").value(4)).andExpect(jsonPath("$.counts.TAB_SWITCH").value(2)).andExpect(jsonPath("$.counts.COPY").value(0));
            mvc.perform(get("/sessions/exam/"+exam+"/integrity/events?type=TAB_SWITCH&size=1").header("Authorization",token(actor,"TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
            mvc.perform(get("/sessions/"+session+"/integrity/events").header("Authorization",token(actor,"TEACHER"))).andExpect(status().isOk());
        }
        for(String path:List.of("/sessions/"+session+"/integrity/events","/sessions/"+session+"/integrity/summary","/sessions/"+session+"/integrity/audit","/sessions/exam/"+exam+"/integrity/events")) {
            mvc.perform(get(path).header("Authorization",token(user,"STUDENT"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization",token(other,"TEACHER"))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/sessions/exam/"+UUID.randomUUID()+"/integrity/events").header("Authorization",token(owner,"TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/"+session+"/integrity/events?size=101").header("Authorization",token(owner,"TEACHER"))).andExpect(status().isBadRequest());
    }
    @Test void rateRaceRetryAndCoreSubmissionIndependent()throws Exception {
        var accepted=new ArrayList<Ingest>();for(int i=0;i<3;i++){var input=event(EventType.COPY);accepted.add(input);integrity.ingest(session,user,"STUDENT",input);}
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var results=new ArrayList<Future<Integer>>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();try{integrity.ingest(session,user,"STUDENT",event(EventType.COPY));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));gate.countDown();var codes=List.of(results.get(0).get(),results.get(1).get());assertTrue(codes.contains(200));assertTrue(codes.contains(429));}finally{pool.shutdownNow();}
        assertTrue(integrity.ingest(session,user,"STUDENT",accepted.get(0)).duplicate());
        core.saveAnswer(session,user,qid,"still saved");assertEquals("still saved",core.get(session,user).answers().get(qid));
        core.submitExam(session,user);core.submitExam(session,user);assertEquals(ExamSession.Status.SUBMITTED,sessions.findById(session).orElseThrow().getStatus());assertTrue(submissions.existsById(session));
    }
    @Test void policySnapshotDisabledSignalsAndTimeout()throws Exception {
        assertEquals(policy,integrity.policy(session,user,"STUDENT"));
        when(exams.authorize(eq(exam),any(),anyString())).thenReturn(new ExamClient.ExamDto(exam,"Changed provider",10,5,3,null,false,false));
        assertEquals(session,core.startExam(exam,user).sessionId());assertEquals(policy,integrity.policy(session,user,"STUDENT"));
        mvc.perform(get("/sessions/"+session+"/integrity/policy").header("Authorization",token(other,"STUDENT"))).andExpect(status().isForbidden());
        var disabled=core.startExam(exam,other).sessionId();assertThrows(ResponseStatusException.class,()->integrity.ingest(disabled,other,"STUDENT",event(EventType.COPY)));
        db.update("update exam_sessions set expires_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),session);
        var expired=integrity.ingest(session,user,"STUDENT",event(EventType.EXIT_FULLSCREEN));
        assertEquals(NotCountedReason.STALE,expired.notCountedReason());assertEquals(0,expired.warningCount());
        core.autoSubmitExam(session);assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(session).orElseThrow().getStatus());
    }
    @Test void boundedInputAndClientCannotSupplyTrustedIdentity()throws Exception {
        for(String extra:List.of("userId","examId","source","severity","receivedAt")) {
            String body="{\"clientEventId\":\""+UUID.randomUUID()+"\",\"type\":\"COPY\",\""+extra+"\":\"untrusted\"}";
            mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(user,"STUDENT")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(user,"STUDENT")).contentType("application/json").content(" ".repeat(4097))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(user,"STUDENT")).contentType("application/json").content(json.writeValueAsString(event(EventType.COPY))+" {} ")).andExpect(status().isBadRequest());
        postEvent(user,"STUDENT",new Ingest(UUID.randomUUID(),EventType.COPY,Instant.now().minusSeconds(604801),null),400);
        for(String body:List.of(
            "{\"clientEventId\":\""+UUID.randomUUID()+"\",\"type\":1}",
            "{\"clientEventId\":\""+UUID.randomUUID()+"\",\"type\":\"COPY\",\"type\":\"PASTE\"}",
            "{\"clientEventId\":\""+UUID.randomUUID()+"\",\"type\":\"NETWORK_RECONNECT\",\"metadata\":{\"durationMs\":1.5}}"))
            mvc.perform(post("/sessions/"+session+"/integrity/events").header("Authorization",token(user,"STUDENT")).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertEquals(0,events.countBySessionId(session));
    }
    @Test void allSupportedSignalsUseAggregateWarningBudget() {
        int index=0;
        for(var type:EventType.values()) {
            if(index>0 && index%4==0){core.submitExam(session,user);session=core.startExam(exam,user).sessionId();}
            var receipt=integrity.ingest(session,user,"STUDENT",event(type));
            assertEquals(type,receipt.event().type());
            assertEquals(3,receipt.warningLimit());assertFalse(receipt.autoSubmitted());
            index++;
        }
    }
    @Test void leaveTransitionCoalescesNetworkIsAuditOnlyAndStaleIsNeverCounted() {
        Instant t0=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("update exam_sessions set start_time=? where id=?",java.sql.Timestamp.from(t0.minusSeconds(1)),session);
        var leave=integrity.ingest(session,user,"STUDENT",at(EventType.TAB_SWITCH,t0));
        assertTrue(leave.counted());assertNull(leave.notCountedReason());assertEquals(1,leave.warningCount());
        // Window blur of the same leave transition (within 1.5s) is one budget unit.
        var blur=integrity.ingest(session,user,"STUDENT",at(EventType.WINDOW_BLUR,t0.plusMillis(400)));
        assertFalse(blur.counted());assertEquals(NotCountedReason.COALESCED,blur.notCountedReason());assertEquals(1,blur.warningCount());
        assertEquals(leave.event().id(),events.findById(blur.event().id()).orElseThrow().getCoalescedInto());
        var offline=integrity.ingest(session,user,"STUDENT",at(EventType.NETWORK_DISCONNECT,t0.plusSeconds(5)));
        assertFalse(offline.counted());assertEquals(NotCountedReason.NOT_BUDGET_SIGNAL,offline.notCountedReason());assertEquals(1,offline.warningCount());
        var stale=integrity.ingest(session,user,"STUDENT",at(EventType.TAB_SWITCH,t0.minusSeconds(30)));
        assertEquals(NotCountedReason.STALE,stale.notCountedReason());assertEquals(1,stale.warningCount());
        assertEquals(4,events.countBySessionId(session));assertEquals(ExamSession.Status.IN_PROGRESS,sessions.findById(session).orElseThrow().getStatus());
    }
    static void sleepPastCoalesceWindow(){try{Thread.sleep(400);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @Test void clientTimeCannotMergeANewLeaveIntoAnEarlierCountedUnit() {
        Instant t0=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("update exam_sessions set start_time=? where id=?",java.sql.Timestamp.from(t0.minusSeconds(1)),session);
        var first=integrity.ingest(session,user,"STUDENT",at(EventType.TAB_SWITCH,t0));assertTrue(first.counted());
        sleepPastCoalesceWindow();
        // A new leave claims the old unit's client time; receipt time decides, so it is still counted.
        var forged=integrity.ingest(session,user,"STUDENT",at(EventType.WINDOW_BLUR,t0.plusMillis(100)));
        assertTrue(forged.counted());assertNull(forged.notCountedReason());assertEquals(2,forged.warningCount());
        assertEquals(t0.plusMillis(100),forged.event().occurredAt()); // client time kept as evidence only
    }
    Ingest at(EventType type,Instant when){return new Ingest(UUID.randomUUID(),type,when,null);}
    @Test void budgetWarnsAtLimitThenAutoSubmitsOnceWithSystemAudit() {
        core.saveAnswer(session,user,qid,"saved");
        Instant t0=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update("update exam_sessions set start_time=? where id=?",java.sql.Timestamp.from(t0.minusSeconds(1)),session);
        assertEquals(1,integrity.ingest(session,user,"STUDENT",at(EventType.TAB_SWITCH,t0)).warningCount());
        sleepPastCoalesceWindow();
        assertEquals(2,integrity.ingest(session,user,"STUDENT",at(EventType.WINDOW_BLUR,t0.plusSeconds(10))).warningCount()); // a separate leave
        var warning=integrity.ingest(session,user,"STUDENT",at(EventType.PAGE_RELOAD,t0.plusSeconds(20)));
        assertTrue(warning.warningRecommended());assertEquals(3,warning.warningCount());assertFalse(warning.autoSubmitted());
        var trigger=at(EventType.EXIT_FULLSCREEN,t0.plusSeconds(30));var submitted=integrity.ingest(session,user,"STUDENT",trigger);
        assertTrue(submitted.autoSubmitted());assertEquals(4,submitted.warningCount());assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(session).orElseThrow().getStatus());
        assertEquals("saved",core.getSessionDetails(session).answers().getFirst().answerContent());assertTrue(submissions.existsById(session));
        assertTrue(integrity.ingest(session,user,"STUDENT",trigger).duplicate());assertEquals(4,events.countBySessionId(session));
        var audit=integrityAudits.findAll().stream().filter(a->a.getExamId().equals(exam)).toList();assertEquals(1,audit.size());assertEquals("SYSTEM",audit.getFirst().getActorType());assertEquals(4,audit.getFirst().getEventCount());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->core.saveAnswer(session,user,qid,"late")).getStatusCode().value());
        // Reopening for this Student is a new granted attempt; the auto-submitted attempt and its result stay.
        UUID next=core.startExam(exam,user).sessionId();assertNotEquals(session,next);
        var fresh=integrity.ingest(next,user,"STUDENT",event(EventType.TAB_SWITCH));assertEquals(1,fresh.warningCount());assertFalse(fresh.autoSubmitted());
        assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(session).orElseThrow().getStatus());
    }
    @Test void wholeExamLockSubmitsActiveAttemptsBlocksStartAndUnlockDoesNotResurrect() {
        UUID second=core.startExam(exam,other).sessionId();
        var locked=core.lockIntegrityExam(exam,proctor,"Manual integrity hold");assertEquals(2,locked.activeSessionsSubmitted());assertTrue(integrityLocks.existsById(exam));
        assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(session).orElseThrow().getStatus());assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(second).orElseThrow().getStatus());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->core.startExam(exam,user)).getStatusCode().value());
        assertEquals(2,integrityAudits.findAll().stream().filter(a->a.getExamId().equals(exam)&&"EXAM_STAFF".equals(a.getActorType())).count());core.unlockIntegrityExam(exam,proctor);
        UUID next=core.startExam(exam,user).sessionId();assertNotEquals(session,next);assertEquals(ExamSession.Status.AUTO_SUBMITTED,sessions.findById(session).orElseThrow().getStatus());
    }
    @Test void sessionTotalCeilingProtectsCore() {
        var s=sessions.findById(session).orElseThrow();
        for(int i=0;i<10;i++)events.saveAndFlush(new IntegrityEvent(s,event(EventType.COPY),Instant.now().minusSeconds(120),Instant.now().minusSeconds(120),NotCountedReason.NOT_BUDGET_SIGNAL,null));
        var failure=assertThrows(ResponseStatusException.class,()->integrity.ingest(session,user,"STUDENT",event(EventType.COPY)));
        assertEquals(429,failure.getStatusCode().value());core.submitExam(session,user);
        assertEquals(ExamSession.Status.SUBMITTED,sessions.findById(session).orElseThrow().getStatus());
    }
    @Test void migrationIdentityDedupAndImmutabilityConstraints() {
        var input=event(EventType.COPY);var stored=integrity.ingest(session,user,"STUDENT",input).event();
        assertThrows(Exception.class,()->db.update("update integrity_events set severity='MEDIUM' where id=?",stored.id()));
        assertThrows(Exception.class,()->db.update("insert into integrity_events select ?,client_event_id,exam_id,session_id,user_id,type,occurred_at,received_at,severity,metadata,source,counted,not_counted_reason,coalesced_into,effective_at from integrity_events where id=?",UUID.randomUUID(),stored.id()));
        assertThrows(Exception.class,()->db.update("insert into integrity_events select ?,?,exam_id,session_id,?,type,occurred_at,received_at,severity,metadata,source,counted,not_counted_reason,coalesced_into,effective_at from integrity_events where id=?",UUID.randomUUID(),UUID.randomUUID(),other,stored.id()));
        assertEquals(1,events.countBySessionId(session));
    }
}
