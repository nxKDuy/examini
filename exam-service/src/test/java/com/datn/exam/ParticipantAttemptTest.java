package com.datn.exam;

import com.datn.exam.client.IdentityClient;
import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.client.SessionClient;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.ExamParticipant;
import com.datn.exam.repository.*;
import com.datn.exam.service.ExamCore;
import com.datn.exam.service.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","identity.token=test-identity","exam.events.outbox-initial-delay-ms=3600000","exam.events.outbox-poll-ms=3600000","exam.schedule.sweep-ms=3600000","exam.start-admission-reconcile-ms=3600000"})
@AutoConfigureMockMvc
@Import(ParticipantAttemptTest.ClockConfig.class)
class ParticipantAttemptTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ExamRepository exams;@Autowired ExamParticipantRepository rows;
    @Autowired AttemptGrantRepository grants;@Autowired ExamOutboxRepository outbox;@Autowired ExamAuditRepository audits;
    @Autowired ExamCore core;@Autowired ScheduleService schedules;@Autowired MutableClock clock;
    @MockBean SessionClient sessions;@MockBean IdentityClient identities;@MockBean QuestionBankClient questions;

    UUID owner,manager,proctor,stranger,s1,s2,s3,teacherUser;Instant base;
    final Map<UUID,IdentityClient.Identity> directory=new HashMap<>();

    @BeforeEach void setup() {
        owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();stranger=UUID.randomUUID();
        s1=UUID.randomUUID();s2=UUID.randomUUID();s3=UUID.randomUUID();teacherUser=UUID.randomUUID();
        base=Instant.parse("2030-03-10T10:00:00Z");clock.set(base);directory.clear();
        for(UUID s:List.of(s1,s2,s3))directory.put(s,new IdentityClient.Identity(s,"Student "+s.toString().substring(0,4),s.toString().substring(0,8)+"@example.test","STUDENT"));
        directory.put(teacherUser,new IdentityClient.Identity(teacherUser,"Teacher","teacher@example.test","TEACHER"));
        when(identities.resolve(any(),eq("test-identity"))).thenAnswer(inv -> {
            IdentityClient.Selector sel=inv.getArgument(0);
            return directory.values().stream().filter(i -> sel.userIds()!=null ? sel.userIds().contains(i.userId()) : sel.emails().contains(i.email())).toList();
        });
        when(sessions.eligibility(any(),any(),eq("test-internal"))).thenReturn(new SessionClient.Eligibility(0,false));
        when(sessions.availability(any(),any(),eq("test-internal"))).thenReturn(new SessionClient.Availability(0,false,null,0,null,null));
        when(sessions.attemptSummary(any(),anyList(),eq("test-internal"))).thenAnswer(inv -> ((List<UUID>)inv.getArgument(1)).stream()
                .map(u -> new SessionClient.AttemptSummary(u,0,0,0,false,null,null,null)).toList());
    }

    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("type","access").expiration(new Date(System.currentTimeMillis()+120000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}
    Exam exam(Exam.Status status,Exam.AccessMode mode,int max,int attemptLimit) {
        var e=Exam.builder().code("C"+UUID.randomUUID().toString().substring(0,8)).title("Participants").durationMinutes(30).status(status).createdBy(owner)
                .startAt(base.minusSeconds(600)).endAt(base.plusSeconds(7200)).timezone("UTC").accessMode(mode).maxParticipants(max).maxConcurrentSessions(Math.min(50,max)).attemptLimit(attemptLimit).build();
        e.getMembers().put(manager,Exam.MemberRole.MANAGER);e.getMembers().put(proctor,Exam.MemberRole.PROCTOR);
        return exams.saveAndFlush(e);
    }
    ResultActions doPost(UUID actor,String role,String url,Object body)throws Exception{return mvc.perform(post(url).header("Authorization",token(actor,role)).contentType(MediaType.APPLICATION_JSON).content(body==null?"":json.writeValueAsString(body)));}
    ResultActions doGet(UUID actor,String role,String url)throws Exception{return mvc.perform(get(url).header("Authorization",token(actor,role)));}
    JsonNode body(ResultActions r)throws Exception{return json.readTree(r.andReturn().getResponse().getContentAsString());}
    static Map<String,Object> ids(UUID... ids){return Map.of("userIds",List.of(ids));}
    static Map<String,Object> grant(Integer q,String reason,String request){var m=new HashMap<String,Object>();if(q!=null)m.put("quantity",q);if(reason!=null)m.put("reason",reason);if(request!=null)m.put("requestId",request);return m;}

    @Test void deletingDraftRemovesParticipantRowsAndGrantsButNonDraftIs409()throws Exception {
        var d=exam(Exam.Status.DRAFT,Exam.AccessMode.ASSIGNED,10,1);
        doPost(owner,"TEACHER","/exams/"+d.getId()+"/participants",ids(s1,s2)).andExpect(status().isOk());
        doPost(owner,"TEACHER","/exams/"+d.getId()+"/participants/"+s2+"/revoke",Map.of("reason","soft revoke")).andExpect(status().isOk());
        mvc.perform(delete("/exams/"+d.getId()).header("Authorization",token(owner,"TEACHER"))).andExpect(status().isOk());
        assertFalse(exams.existsById(d.getId()));assertEquals(0,rows.findByExamIdAndUserIdIn(d.getId(),List.of(s1,s2)).size());
        var p=exam(Exam.Status.PUBLISHED,Exam.AccessMode.ASSIGNED,10,1);core.addParticipant(p,owner,s1);
        mvc.perform(delete("/exams/"+p.getId()).header("Authorization",token(owner,"TEACHER"))).andExpect(status().isConflict());
        assertTrue(exams.existsById(p.getId()));
    }

    @Test void internalIdentityUsesTrustedProviderAndParticipantBoundary()throws Exception{
        var exam=exam(Exam.Status.PUBLISHED,Exam.AccessMode.ASSIGNED,10,1);core.addParticipant(exam,owner,s1);
        mvc.perform(get("/exams/"+exam.getId()+"/internal/identity/"+s1).header("X-Service-Token","test-internal"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(s1.toString())).andExpect(jsonPath("$.displayName").value(directory.get(s1).displayName()));
        mvc.perform(get("/exams/"+exam.getId()+"/internal/identity/"+stranger).header("X-Service-Token","test-internal")).andExpect(status().isNotFound());
        mvc.perform(get("/exams/"+exam.getId()+"/internal/identity/"+s1).header("X-Service-Token","wrong")).andExpect(status().isUnauthorized());
    }

    @Test void assignByIdAndEmailIsBulkIdempotentAndRespectsStateRolesAndProvider()throws Exception {
        for(Exam.Status status:List.of(Exam.Status.DRAFT,Exam.Status.PUBLISHED,Exam.Status.OPEN)) {
            var e=exam(status,Exam.AccessMode.ASSIGNED,10,1);UUID unknown=UUID.randomUUID();
            var first=body(doPost(owner,"TEACHER","/exams/"+e.getId()+"/participants",ids(s1,s2,unknown,teacherUser)).andExpect(status().isOk()));
            Map<String,String> outcomes=new HashMap<>();first.get("results").forEach(r->outcomes.put(r.get("input").asText(),r.get("outcome").asText()));
            assertEquals("ASSIGNED",outcomes.get(s1.toString()));assertEquals("ASSIGNED",outcomes.get(s2.toString()));
            assertEquals("NOT_FOUND",outcomes.get(unknown.toString()));assertEquals("NOT_A_STUDENT",outcomes.get(teacherUser.toString()));
            assertEquals(2,first.get("counts").get("eligible").asInt());
            var again=body(doPost(manager,"TEACHER","/exams/"+e.getId()+"/participants",ids(s1)).andExpect(status().isOk()));
            assertEquals("ALREADY_PARTICIPANT",again.get("results").get(0).get("outcome").asText());
            assertEquals(2,rows.countByExamIdAndStatus(e.getId(),ExamParticipant.Status.ACTIVE));
        }
        var e=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,10,1);
        var byEmail=body(doPost(owner,"TEACHER","/exams/"+e.getId()+"/participants",Map.of("emails",List.of(" "+directory.get(s3).email().toUpperCase()+" "))).andExpect(status().isOk()));
        assertEquals("ASSIGNED",byEmail.get("results").get(0).get("outcome").asText());assertEquals(s3.toString(),byEmail.get("results").get(0).get("userId").asText());
        doPost(owner,"TEACHER","/exams/"+e.getId()+"/participants",Map.of("userIds",List.of(s1),"emails",List.of("a@example.test"))).andExpect(status().isBadRequest());
        doPost(owner,"TEACHER","/exams/"+e.getId()+"/participants",Map.of("userIds",List.of())).andExpect(status().isBadRequest());
        doPost(owner,"TEACHER","/exams/"+e.getId()+"/participants",Map.of("userIds",Collections.nCopies(101,UUID.randomUUID()))).andExpect(status().isBadRequest());
        doPost(proctor,"TEACHER","/exams/"+e.getId()+"/participants",ids(s1)).andExpect(status().isForbidden());
        doPost(stranger,"TEACHER","/exams/"+e.getId()+"/participants",ids(s1)).andExpect(status().isForbidden());
        doPost(stranger,"ADMIN","/exams/"+e.getId()+"/participants",ids(s1)).andExpect(status().isForbidden());
        doPost(s1,"STUDENT","/exams/"+e.getId()+"/participants",ids(s1)).andExpect(status().isForbidden());
        var closed=exam(Exam.Status.CLOSED,Exam.AccessMode.ASSIGNED,10,1);
        doPost(owner,"TEACHER","/exams/"+closed.getId()+"/participants",ids(s1)).andExpect(status().isConflict());
        // Provider failures fail closed: nothing is assigned.
        when(identities.resolve(any(),eq("test-identity"))).thenThrow(new RuntimeException("down"));
        var fresh=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,10,1);
        doPost(owner,"TEACHER","/exams/"+fresh.getId()+"/participants",ids(s1)).andExpect(status().isServiceUnavailable());
        assertEquals(0,rows.countByExamIdAndStatus(fresh.getId(),ExamParticipant.Status.ACTIVE));
    }

    @Test void revocationKeepsHistoryFreesEligibleCapacityAndReinstateNeedsRoom()throws Exception {
        var e=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,2,1);String base="/exams/"+e.getId()+"/participants";
        doPost(owner,"TEACHER",base,ids(s1,s2)).andExpect(status().isOk());
        var full=body(doPost(owner,"TEACHER",base,ids(s3)));assertEquals("CAPACITY_FULL",full.get("results").get(0).get("outcome").asText());
        var revoked=body(doPost(manager,"TEACHER",base+"/"+s1+"/revoke",Map.of("reason","Wrong class")).andExpect(status().isOk()));
        assertEquals("REVOKED",revoked.get("status").asText());assertEquals("Wrong class",revoked.get("revocationReason").asText());
        doPost(manager,"TEACHER",base+"/"+s1+"/revoke",null).andExpect(status().isOk()); // idempotent
        assertEquals("ASSIGNED",body(doPost(owner,"TEACHER",base,ids(s3))).get("results").get(0).get("outcome").asText());
        assertEquals("CAPACITY_FULL",body(doPost(owner,"TEACHER",base,ids(s1))).get("results").get(0).get("outcome").asText());
        var roster=body(doGet(proctor,"TEACHER",base).andExpect(status().isOk()));
        assertEquals(3,roster.get("counts").get("registered").asInt());assertEquals(2,roster.get("counts").get("eligible").asInt());assertEquals(1,roster.get("counts").get("revoked").asInt());
        var onlyRevoked=body(doGet(proctor,"TEACHER",base+"?status=REVOKED"));assertEquals(1,onlyRevoked.get("content").size());assertEquals(s1.toString(),onlyRevoked.get("content").get(0).get("userId").asText());
        assertTrue(rows.existsByExamIdAndUserId(e.getId(),s1));
        doPost(proctor,"TEACHER",base+"/"+s2+"/revoke",null).andExpect(status().isForbidden());
        mvc.perform(delete(base+"/"+s2).header("Authorization",token(stranger,"TEACHER"))).andExpect(status().isForbidden());
        // Room appears when another participant is revoked, then the first can be reinstated.
        doPost(owner,"TEACHER",base+"/"+s2+"/revoke",null).andExpect(status().isOk());
        assertEquals("REINSTATED",body(doPost(owner,"TEACHER",base,ids(s1))).get("results").get(0).get("outcome").asText());
        var actions=audits.findByExamIdOrderByTimestampAsc(e.getId()).stream().map(a->a.getAction()).toList();
        assertTrue(actions.containsAll(List.of("PARTICIPANT_ASSIGNED","PARTICIPANT_REVOKED","PARTICIPANT_REINSTATED")));
        assertTrue(outbox.findAll().stream().filter(o->o.getEventType().equals("exam.participant.changed")).map(o->{try{return json.readTree(o.getPayload());}catch(Exception ex){throw new RuntimeException(ex);}})
                .anyMatch(n->"REVOKED".equals(n.path("cause").asText())&&"Wrong class".equals(n.path("publicReason").asText())&&e.getId().toString().equals(n.path("examId").asText())&&n.path("eligibleCount").asInt()==1&&n.path("registeredCount").asInt()==2&&n.path("recipientRevision").asLong()>0));
    }

    @Test void joinSourceFollowIndependenceAndRevokedUsersCannotJoinOrStart()throws Exception {
        var pub=exam(Exam.Status.OPEN,Exam.AccessMode.PUBLIC,5,1);
        mvc.perform(put("/exams/"+pub.getId()+"/follow").header("Authorization",token(s1,"STUDENT"))).andExpect(status().isOk());
        assertFalse(rows.existsByExamIdAndUserId(pub.getId(),s1));assertEquals(0,core.counts(pub.getId()).registered());
        assertThrows(ResponseStatusException.class,()->core.authorizeStart(pub.getId(),s1)); // follow never grants Start
        core.join(pub.getId(),s1,null);
        assertEquals("PUBLIC",rows.findByExamIdAndUserId(pub.getId(),s1).orElseThrow().getSource());
        core.join(pub.getId(),s1,null);assertEquals(1,core.counts(pub.getId()).registered()); // no duplicates
        core.authorizeStart(pub.getId(),s1);
        core.assign(pub.getId(),owner,s1,true);
        var denied=assertThrows(ResponseStatusException.class,()->core.join(pub.getId(),s1,null));assertEquals(403,denied.getStatusCode().value());assertEquals("PARTICIPATION_REVOKED",denied.getReason());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->core.authorizeStart(pub.getId(),s1)).getStatusCode().value());
        var code=exam(Exam.Status.OPEN,Exam.AccessMode.JOIN_CODE,5,1);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->core.join(code.getId(),s2,"wrong")).getStatusCode().value());
        core.join(code.getId(),s2,code.getCode());assertEquals("JOIN_CODE",rows.findByExamIdAndUserId(code.getId(),s2).orElseThrow().getSource());
        var assigned=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,5,1);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->core.join(assigned.getId(),s3,null)).getStatusCode().value());
        var capacity=exam(Exam.Status.OPEN,Exam.AccessMode.PUBLIC,1,1);core.join(capacity.getId(),s1,null);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->core.join(capacity.getId(),s2,null)).getStatusCode().value());
    }

    @Test void revokedParticipantCanStillResumeButNotStartNewAndAvailabilityAgrees()throws Exception {
        var e=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,5,2);core.assign(e.getId(),owner,s1,false);core.assign(e.getId(),owner,s1,true);
        var notResumable=schedules.availability(e.getId(),s1,null);
        assertFalse(notResumable.canStart());assertFalse(notResumable.canJoin());assertEquals("PARTICIPATION_REVOKED",notResumable.reasonCode());assertTrue(notResumable.canView());
        when(sessions.availability(eq(e.getId()),eq(s1),eq("test-internal"))).thenReturn(new SessionClient.Availability(1,true,base.plusSeconds(600),1,UUID.randomUUID(),"IN_PROGRESS"));
        var resumable=schedules.availability(e.getId(),s1,null);
        assertTrue(resumable.canResume());assertNull(resumable.reasonCode()); // active session keeps its deadline; Session service resumes without re-authorizing
        assertThrows(ResponseStatusException.class,()->core.authorizeStart(e.getId(),s1));
    }

    @Test void perStudentGrantsExtendOnlyThatStudentIdempotentlyAndNeverBypassScheduleOrCapacity()throws Exception {
        var e=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,5,1);String path="/exams/"+e.getId()+"/participants/"+s1+"/attempt-grants";
        core.assign(e.getId(),owner,s1,false);core.assign(e.getId(),owner,s2,false);
        when(sessions.eligibility(eq(e.getId()),any(),eq("test-internal"))).thenReturn(new SessionClient.Eligibility(1,false));
        when(sessions.availability(eq(e.getId()),any(),eq("test-internal"))).thenReturn(new SessionClient.Availability(1,false,null,0,UUID.randomUUID(),"SUBMITTED"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->core.authorizeStart(e.getId(),s1)).getStatusCode().value());
        assertEquals(0,schedules.availability(e.getId(),s1,null).attemptsRemaining());
        doPost(owner,"TEACHER",path,grant(1,null,"request-00000001")).andExpect(status().isBadRequest());
        doPost(owner,"TEACHER",path,grant(1,"  ","request-00000001")).andExpect(status().isBadRequest());
        doPost(owner,"TEACHER",path,grant(1,"Technical failure",null)).andExpect(status().isBadRequest());
        doPost(owner,"TEACHER",path,grant(0,"Technical failure","request-00000001")).andExpect(status().isBadRequest());
        doPost(proctor,"TEACHER",path,grant(1,"Technical failure","request-00000001")).andExpect(status().isForbidden());
        doPost(stranger,"TEACHER",path,grant(1,"Technical failure","request-00000001")).andExpect(status().isForbidden());
        doPost(owner,"TEACHER","/exams/"+e.getId()+"/participants/"+s3+"/attempt-grants",grant(1,"x y","request-00000001")).andExpect(status().isNotFound());
        var granted=body(doPost(manager,"TEACHER",path,grant(null,"Technical failure","request-00000001")).andExpect(status().isOk()));
        assertEquals(1,granted.get("grant").get("quantity").asInt());assertEquals(2,granted.get("effectiveAttemptLimit").asInt());assertFalse(granted.get("replayed").asBoolean());
        var replay=body(doPost(manager,"TEACHER",path,grant(null,"Technical failure","request-00000001")).andExpect(status().isOk()));
        assertTrue(replay.get("replayed").asBoolean());assertEquals(1,rows.findByExamIdAndUserId(e.getId(),s1).orElseThrow().getExtraAttempts());assertEquals(1,grants.findByExamIdAndUserIdOrderByCreatedAtAsc(e.getId(),s1).size());
        doPost(manager,"TEACHER",path,grant(2,"Different","request-00000001")).andExpect(status().isConflict());
        core.authorizeStart(e.getId(),s1); // 1 used < 2 effective
        assertEquals(409,assertThrows(ResponseStatusException.class,()->core.authorizeStart(e.getId(),s2)).getStatusCode().value()); // other Student unaffected
        assertEquals(1,schedules.availability(e.getId(),s1,null).attemptsRemaining());
        assertEquals(1,exams.findById(e.getId()).orElseThrow().getAttemptLimit()); // Exam limit untouched
        mvc.perform(get("/exams/"+e.getId()+"/internal/attempt-limit").param("user",s1.toString()).header("X-Service-Token","test-internal")).andExpect(status().isOk()).andExpect(content().string("2"));
        mvc.perform(get("/exams/"+e.getId()+"/internal/attempt-limit").param("user",s2.toString()).header("X-Service-Token","test-internal")).andExpect(content().string("1"));
        mvc.perform(get("/exams/"+e.getId()+"/internal/attempt-limit").param("user",s1.toString())).andExpect(status().isUnauthorized());
        long eligibleBefore=core.counts(e.getId()).eligible();
        doPost(owner,"TEACHER",path,grant(3,"More time to retake","request-00000002")).andExpect(status().isOk());
        assertEquals(eligibleBefore,core.counts(e.getId()).eligible()); // capacity not consumed by grants
        clock.set(base.minusSeconds(3600)); // before startAt: a grant never bypasses the schedule
        assertEquals(409,assertThrows(ResponseStatusException.class,()->core.authorizeStart(e.getId(),s1)).getStatusCode().value());
        clock.set(base);
        core.assign(e.getId(),owner,s1,true);
        doPost(owner,"TEACHER",path,grant(1,"After revoke","request-00000003")).andExpect(status().isConflict());
        var history=body(doGet(owner,"TEACHER",path));assertEquals(2,history.size());
        doGet(proctor,"TEACHER",path).andExpect(status().isForbidden());
        assertTrue(outbox.findAll().stream().anyMatch(o->o.getPayload().contains("ATTEMPT_GRANTED")&&o.getPayload().contains("Technical failure")));
    }

    @Test void rosterPaginatesFiltersAndBatchesSessionLookups()throws Exception {
        var e=exam(Exam.Status.OPEN,Exam.AccessMode.PUBLIC,50,2);String base="/exams/"+e.getId()+"/participants";
        List<UUID> users=new ArrayList<>();
        for(int i=0;i<5;i++){UUID u=UUID.randomUUID();users.add(u);rows.saveAndFlush(new ExamParticipant(e.getId(),u,i%2==0?"ASSIGNED":"PUBLIC",owner,this.base.plusSeconds(i)));}
        when(sessions.attemptSummary(eq(e.getId()),anyList(),eq("test-internal"))).thenAnswer(inv -> ((List<UUID>)inv.getArgument(1)).stream().map(u -> new SessionClient.AttemptSummary(u,2,1,1,false,UUID.randomUUID(),"SUBMITTED",2)).toList());
        clearInvocations(sessions);
        var page=body(doGet(manager,"TEACHER",base+"?page=1&size=2").andExpect(status().isOk()));
        assertEquals(2,page.get("content").size());assertEquals(5,page.get("totalElements").asLong());assertEquals(3,page.get("totalPages").asInt());
        verify(sessions,times(1)).attemptSummary(eq(e.getId()),anyList(),anyString()); // one batch per page, never per row
        var row=page.get("content").get(0);
        assertEquals(2,row.get("attempts").get("used").asInt());assertEquals(1,row.get("attempts").get("invalidated").asInt());assertEquals(1,row.get("attempts").get("valid").asInt());assertEquals(0,row.get("attempts").get("remaining").asInt());
        assertEquals(3,body(doGet(owner,"TEACHER",base+"?source=ASSIGNED")).get("totalElements").asInt());
        assertEquals(1,body(doGet(owner,"TEACHER",base+"?userId="+users.get(0))).get("totalElements").asInt());
        doGet(owner,"TEACHER",base+"?status=BOGUS").andExpect(status().isBadRequest());
        doGet(owner,"TEACHER",base+"?size=500").andExpect(status().isBadRequest());
        doGet(stranger,"TEACHER",base).andExpect(status().isForbidden());
        doGet(users.get(0),"STUDENT",base).andExpect(status().isForbidden());
        assertEquals(5,body(doGet(proctor,"TEACHER",base+"/counts")).get("eligible").asInt());
    }

    @Test void studentSeesOnlyOwnParticipationHistoryAndPublicReasons()throws Exception {
        var e=exam(Exam.Status.OPEN,Exam.AccessMode.ASSIGNED,5,1);core.assign(e.getId(),owner,s1,false);core.assign(e.getId(),owner,s2,false);
        core.grantAttempts(core.load(e.getId()),owner,s1,2,"Network outage","grant-request-1");
        UUID invalid=UUID.randomUUID();
        when(sessions.attempts(eq(e.getId()),eq(s1),eq("test-internal"))).thenReturn(List.of(
                new SessionClient.Attempt(invalid,1,"SUBMITTED",LocalDateTime.now(),LocalDateTime.now(),base,true,base,"Plagiarism confirmed"),
                new SessionClient.Attempt(UUID.randomUUID(),2,"IN_PROGRESS",LocalDateTime.now(),null,base.plusSeconds(60),false,null,null)));
        var mine=body(doGet(s1,"STUDENT","/exams/"+e.getId()+"/participation/me").andExpect(status().isOk()));
        assertEquals("ACTIVE",mine.get("status").asText());assertEquals(3,mine.get("effectiveAttemptLimit").asInt());assertEquals(2,mine.get("used").asInt());assertEquals(1,mine.get("remaining").asInt());assertTrue(mine.get("activeAttempt").asBoolean());
        assertTrue(mine.get("attempts").get(0).get("invalidated").asBoolean());assertEquals("Plagiarism confirmed",mine.get("attempts").get(0).get("invalidationReason").asText());
        assertEquals("Network outage",mine.get("grants").get(0).get("reason").asText());
        String text=mine.toString();assertFalse(text.contains(s2.toString()));assertFalse(text.contains(owner.toString())); // no foreign users or staff identities
        doGet(stranger,"STUDENT","/exams/"+e.getId()+"/participation/me").andExpect(status().isNotFound());
        core.revoke(core.load(e.getId()),owner,s1,"Left the course");
        var revoked=body(doGet(s1,"STUDENT","/exams/"+e.getId()+"/participation/me").andExpect(status().isOk()));
        assertEquals("REVOKED",revoked.get("status").asText());assertEquals("Left the course",revoked.get("revocationReason").asText());
    }

    @TestConfiguration static class ClockConfig {
        @Bean @Primary MutableClock participantClock(){return new MutableClock();}
    }
    static final class MutableClock extends Clock {
        private Instant instant=Instant.parse("2030-03-10T10:00:00Z");
        void set(Instant value){instant=value;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant;}
    }
}
