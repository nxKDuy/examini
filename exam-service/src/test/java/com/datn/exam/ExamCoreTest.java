package com.datn.exam;
import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.dto.Snapshot;
import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.*;
import com.datn.exam.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","notification.internal-token=test-notification","question-bank.internal-token=test-qb"})
@AutoConfigureMockMvc
class ExamCoreTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ExamCore core;@Autowired ExamRepository exams;@Autowired ExamAuditRepository audits;
    @Autowired ExamOutboxRepository outbox;@Autowired ScheduleService schedules;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean QuestionBankClient bank;
    @MockBean com.datn.exam.client.SessionClient sessions;
    UUID owner,manager,proctor,student,qid,oid;
    @BeforeEach void setup(){when(sessions.eligibility(any(),any(),anyString())).thenReturn(new com.datn.exam.client.SessionClient.Eligibility(0,false));owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();student=UUID.randomUUID();qid=UUID.randomUUID();oid=UUID.randomUUID();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("APPROVED",1L)));}
    QuestionBankClient.Question question(String status,long version){return new QuestionBankClient.Question(qid,version,status,"MULTIPLE_CHOICE","Original",List.of(new Snapshot.Option(oid,"Yes",0,true),new Snapshot.Option(UUID.randomUUID(),"No",1,false)),null);}
    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}
    UUID create()throws Exception {String body="{\"title\":\"Exam\",\"durationMinutes\":10,\"passingScore\":0,\"type\":\"EXAM\",\"questions\":[{\"questionId\":\""+qid+"\",\"weight\":2.5}]}";
        var response=mvc.perform(post("/exams/manual").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return UUID.fromString(json.readTree(response).path("id").asText());}
    @Test void ownershipPermissionsLifecycleAndSafeSnapshot()throws Exception {
        UUID id=create();UUID second=create();assertNotEquals(id,second);
        core.member(id,owner,manager,Exam.MemberRole.MANAGER,false);core.accept(id,manager,"TEACHER");
        core.member(id,owner,proctor,Exam.MemberRole.PROCTOR,false);core.accept(id,proctor,"TEACHER");
        assertThrows(Exception.class,()->core.member(id,manager,owner,Exam.MemberRole.MANAGER,false));
        assertThrows(Exception.class,()->core.transition(id,proctor,Exam.Status.PUBLISHED));
        assertThrows(Exception.class,()->core.transition(id,student,Exam.Status.PUBLISHED));
        assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.OPEN));
        mvc.perform(get("/exams").header("Authorization",token(manager,"TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        core.assign(id,manager,student,false);core.transition(id,manager,Exam.Status.PUBLISHED);
        assertThrows(Exception.class,()->core.authorizeStart(id,student));
        core.transition(id,manager,Exam.Status.OPEN);core.authorizeStart(id,student);
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("APPROVED",2L)));
        var safe=mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-internal")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));assertTrue(safe.contains("Original"));assertTrue(safe.contains("2.5"));assertEquals(1,json.readTree(safe).get(0).path("questionVersion").asInt());
        assertThrows(Exception.class,()->core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,100,50,1)));
        core.transition(id,owner,Exam.Status.CLOSED);assertThrows(Exception.class,()->core.authorizeStart(id,student));assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.OPEN));
        assertTrue(audits.findByExamIdOrderByTimestampAsc(id).stream().anyMatch(a->a.getAction().equals("EXAM_CLOSED")));
        mvc.perform(get("/exams/"+id+"/internal/grading").header("Authorization",token(student,"STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/"+id+"/internal/grading").header("Authorization",token(owner,"TEACHER")).header("X-Service-Token","test-internal")).andExpect(status().isUnauthorized());
        mvc.perform(get(java.net.URI.create("/exams/"+id+"/inter%6eal/grading")).header("Authorization",token(student,"STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/public").header("Authorization",token(owner,"TEACHER"),token(student,"STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/public").header("X-User-Id",student)).andExpect(status().isUnauthorized());
        mvc.perform(put("/exams/"+id+"/open").header("Authorization",token(proctor,"TEACHER"))).andExpect(status().isForbidden());
    }
    @Test void unscheduledPublishKeepsReminderDefaultsAndDoesNotEmitDuplicateScheduleNotice()throws Exception {
        UUID id=create();
        assertEquals(List.of(1440,15),exams.findById(id).orElseThrow().getReminderOffsetsMinutes());
        core.transition(id,owner,Exam.Status.PUBLISHED);
        var types=outbox.findAll().stream().filter(event->event.getPayload().contains(id.toString())).map(com.datn.exam.entity.ExamOutboxEvent::getEventType).toList();
        assertEquals(List.of("exam.lifecycle.changed"),types);
    }

    @Test void scheduleUpdateUsesOffsetAndRevisionAndPublicPreviewWaitsForStartAt()throws Exception {
        UUID id=create();core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,10,5,2));
        String schedule="{\"startAt\":\"2030-01-01T10:00:00Z\",\"endAt\":\"2030-01-01T11:00:00Z\",\"timezone\":\"UTC\",\"deadlineRule\":\"REQUIRE_FULL_WINDOW\",\"autoOpen\":false,\"autoClose\":true,\"reminderOffsetsMinutes\":[1440,15],\"expectedRevision\":0}";
        mvc.perform(put("/exams/"+id+"/schedule").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(schedule))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scheduleRevision").value(1));
        core.transition(id,owner,Exam.Status.PUBLISHED);
        when(sessions.availability(eq(id),eq(student),anyString())).thenReturn(new com.datn.exam.client.SessionClient.Availability(0,false,null,0,null,null));
        mvc.perform(get("/exams/"+id+"/availability").header("Authorization",token(student,"STUDENT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.canView").value(true)).andExpect(jsonPath("$.canJoin").value(false))
            .andExpect(jsonPath("$.canStart").value(false)).andExpect(jsonPath("$.reasonCode").value("BEFORE_START_AT"));
        var related=outbox.findAll().stream().filter(event->event.getPayload().contains(id.toString())).toList();
        assertEquals(2,related.size());assertEquals(1,related.stream().filter(event->event.getEventType().equals("exam.schedule.changed")).count());
        assertEquals(1,related.stream().filter(event->event.getEventType().equals("exam.lifecycle.changed")).count());
        assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->core.join(id,student,null)).getStatusCode().value());
    }

    @Test void publicFollowCanBeRemovedAfterCloseAndDraftRecipientsExcludeStudents()throws Exception {
        UUID id=create();core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,10,5,1));
        core.assign(id,owner,student,false);
        UUID manager=UUID.randomUUID();core.member(id,owner,manager,Exam.MemberRole.MANAGER,false);core.accept(id,manager,"TEACHER");
        var draftSnapshot=schedules.notificationRecipients(id,"test-notification","test-notification");
        assertEquals(Set.of("OWNER","MANAGER"),draftSnapshot.recipients().stream().map(ScheduleDto.Recipient::relation).collect(java.util.stream.Collectors.toSet()));
        core.transition(id,owner,Exam.Status.PUBLISHED);
        schedules.follow(id,student,true);core.transition(id,owner,Exam.Status.CLOSED);
        long before=exams.findById(id).orElseThrow().getRecipientRevision();
        var removed=schedules.follow(id,student,false);
        assertFalse(removed.followed());assertTrue(removed.participant());assertEquals(before+1,exams.findById(id).orElseThrow().getRecipientRevision());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->schedules.follow(id,student,true));
        var recipientEvents=outbox.findAll().stream().filter(event->event.getEventType().equals("exam.recipients.changed")&&event.getPayload().contains(id.toString())).toList();
        assertEquals(1,recipientEvents.stream().filter(event->event.getPayload().contains("\"cause\": \"FOLLOWED\"")).count());
        assertEquals(1,recipientEvents.stream().filter(event->event.getPayload().contains("\"cause\": \"UNFOLLOWED\"")).count());
        assertEquals("ASSIGNED",jdbc.queryForObject("select source from exam_participants where exam_id=? and user_id=?",String.class,id,student));
    }

    @Test void calendarShowsParticipantCountOnlyToExamStaffAndHidesDraftFromStudents()throws Exception {
        UUID id=create();core.assign(id,owner,student,false);core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,10,5,1));
        core.transition(id,owner,Exam.Status.PUBLISHED);
        when(sessions.availability(eq(id),eq(student),anyString())).thenReturn(new com.datn.exam.client.SessionClient.Availability(0,false,null,0,null,null));
        var teacherResponse=mvc.perform(get("/exams/calendar").param("from","2030-01-01T00:00:00Z").param("to","2030-01-02T00:00:00Z").param("includeUnscheduled","true").param("size","100").header("Authorization",token(owner,"TEACHER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var teacherItems=json.readTree(teacherResponse).path("content");com.fasterxml.jackson.databind.JsonNode teacherItem=null;
        for(var item:teacherItems)if(id.toString().equals(item.path("examId").asText()))teacherItem=item;
        assertNotNull(teacherItem);assertEquals(1,teacherItem.path("participantCount").asInt());assertEquals(10,teacherItem.path("maxParticipants").asInt());
        var studentResponse=mvc.perform(get("/exams/calendar").param("from","2030-01-01T00:00:00Z").param("to","2030-01-02T00:00:00Z").param("includeUnscheduled","true").header("Authorization",token(student,"STUDENT")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var item=json.readTree(studentResponse).path("content").get(0);
        assertTrue(item.get("participantCount").isNull());assertTrue(item.get("maxParticipants").isNull());
        UUID draft=create();
        var draftResponse=mvc.perform(get("/exams/calendar").param("from","2030-01-01T00:00:00Z").param("to","2030-01-02T00:00:00Z").param("includeUnscheduled","true").header("Authorization",token(student,"STUDENT")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(draftResponse.contains(draft.toString()));
    }
    @Test void eligibilityPolicyAndParticipantRace()throws Exception {
        UUID id=create();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("DRAFT",1)));
        assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.PUBLISHED));assertEquals(Exam.Status.DRAFT,exams.findById(id).orElseThrow().getStatus());
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("APPROVED",1)));
        assertThrows(Exception.class,()->core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,200,101,1)));
        core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,1,1,1));core.transition(id,owner,Exam.Status.PUBLISHED);core.transition(id,owner,Exam.Status.OPEN);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();try{core.join(id,UUID.randomUUID(),null);return true;}catch(Exception e){return false;}}));gate.countDown();int accepted=0;for(var result:results)if(result.get())accepted++;assertEquals(1,accepted);}finally{pool.shutdownNow();}
    }
    @Test void assignedAndCodePoliciesAndTeacherInvitation()throws Exception {
        UUID id=create();core.transition(id,owner,Exam.Status.PUBLISHED);core.transition(id,owner,Exam.Status.OPEN);
        assertThrows(Exception.class,()->core.join(id,student,null));
        UUID codeExam=create();core.policy(codeExam,owner,new ExamCore.Policy(Exam.AccessMode.JOIN_CODE,10,2,1));core.transition(codeExam,owner,Exam.Status.PUBLISHED);core.transition(codeExam,owner,Exam.Status.OPEN);
        assertThrows(Exception.class,()->core.join(codeExam,student,"wrong"));core.join(codeExam,student,exams.findById(codeExam).orElseThrow().getCode());
        core.member(codeExam,owner,student,Exam.MemberRole.MANAGER,false);assertThrows(Exception.class,()->core.accept(codeExam,student,"STUDENT"));
        mvc.perform(get("/exams/"+codeExam).header("Authorization",token(student,"STUDENT"))).andExpect(status().isForbidden());
    }
}
