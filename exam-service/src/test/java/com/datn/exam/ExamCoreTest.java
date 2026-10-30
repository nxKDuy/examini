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

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","notification.internal-token=test-notification","analytics.permission-token=test-analytics-permission","question-bank.internal-token=test-qb"})
@AutoConfigureMockMvc
class ExamCoreTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ExamCore core;@Autowired ExamRepository exams;@Autowired ExamAuditRepository audits;
    @Autowired ExamOutboxRepository outbox;@Autowired ScheduleService schedules;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean QuestionBankClient bank;
    @MockBean com.datn.exam.client.SessionClient sessions;
    UUID owner,manager,proctor,student,qid,oid;
    @BeforeEach void setup(){when(sessions.eligibility(any(),any(),anyString())).thenReturn(new com.datn.exam.client.SessionClient.Eligibility(0,false));owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();student=UUID.randomUUID();qid=UUID.randomUUID();oid=UUID.randomUUID();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("ACTIVE",1L,true)));when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(false))).thenReturn(List.of(question("ACTIVE",1L,true)));}
    QuestionBankClient.Question question(String status,long version,Boolean eligible){return new QuestionBankClient.Question(qid,version,status,"MULTIPLE_CHOICE","Original",List.of(new Snapshot.Option(oid,"Yes",0,true),new Snapshot.Option(UUID.randomUUID(),"No",1,false)),null,eligible,"PERSONAL",null,"Frozen author explanation");}
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
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("ACTIVE",2L,true)));
        var safe=mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-internal")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));assertFalse(safe.contains("explanation"));assertTrue(safe.contains("Original"));assertTrue(safe.contains("2.5"));assertEquals(1,json.readTree(safe).get(0).path("questionVersion").asInt());
        var frozen=mvc.perform(get("/exams/"+id+"/internal/grading").header("X-Service-Token","test-internal")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertTrue(frozen.contains("Frozen author explanation"));
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
    @Test void presentationAndSpeakingTypeAreFrozenIntoTheSafeSnapshot()throws Exception {
        var presentation=Map.<String,Object>of("media",List.of(Map.of("id",UUID.randomUUID().toString(),"kind","AUDIO")),"maxPlays",2,
                "passage",Map.of("key",UUID.randomUUID().toString(),"title","Bài đọc","content","Ngày xửa ngày xưa"));
        var speaking=new QuestionBankClient.Question(qid,1L,"ACTIVE","SPEAKING","Nói về gia đình",List.of(),null,true,"PERSONAL",null,null,presentation);
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(speaking));
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(false))).thenReturn(List.of(speaking));
        UUID id=create();core.transition(id,owner,Exam.Status.PUBLISHED);
        mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-internal")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].questionType").value("SPEAKING")).andExpect(jsonPath("$[0].presentation.maxPlays").value(2))
            .andExpect(jsonPath("$[0].presentation.passage.title").value("Bài đọc"));
    }
    @Test void multiCorrectTypesPublishWithTheirKeysHiddenFromStudents()throws Exception {
        var opts=List.of(new com.datn.exam.dto.Snapshot.Option(UUID.randomUUID(),"Ý a",0,true),new com.datn.exam.dto.Snapshot.Option(UUID.randomUUID(),"Ý b",1,false),new com.datn.exam.dto.Snapshot.Option(UUID.randomUUID(),"Ý c",2,true));
        var group=new QuestionBankClient.Question(qid,1L,"ACTIVE","TRUE_FALSE_GROUP","Mỗi ý đúng hay sai?",opts,null,true,"PERSONAL",null,null,null);
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(group));
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(false))).thenReturn(List.of(group));
        UUID id=create();core.transition(id,owner,Exam.Status.PUBLISHED);
        mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-internal")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].questionType").value("TRUE_FALSE_GROUP")).andExpect(jsonPath("$[0].options.length()").value(3))
            .andExpect(jsonPath("$[0].options[0].correct").doesNotExist());
    }
    @Test void adminStatsCountExamsAndResolveTitlesForAdministratorsOnly()throws Exception {
        UUID id=create();
        String admin="Bearer "+Jwts.builder().subject(UUID.randomUUID().toString()).claim("role","ADMIN").claim("systemPrivilege","ADMIN").claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/exams/admin/stats").param("ids",id.toString()).header("Authorization",admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1))).andExpect(jsonPath("$.byStatus.DRAFT").exists())
            .andExpect(jsonPath("$.createdPerDay.length()").value(30)).andExpect(jsonPath("$.titles['"+id+"']").value("Exam"));
        mvc.perform(get("/exams/admin/stats").header("Authorization",token(owner,"TEACHER"))).andExpect(status().isForbidden());
    }
    @Test void paperExportNeedsAPublishedSnapshotAndAManagerAndIsAudited()throws Exception {
        UUID id=create();
        core.member(id,owner,proctor,Exam.MemberRole.PROCTOR,false);core.accept(id,proctor,"TEACHER");
        mvc.perform(get("/exams/"+id+"/paper").header("Authorization",token(owner,"TEACHER"))).andExpect(status().isConflict());
        core.transition(id,owner,Exam.Status.PUBLISHED);
        mvc.perform(get("/exams/"+id+"/paper").header("Authorization",token(owner,"TEACHER"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].content").value("Original"))
            .andExpect(jsonPath("$[0].options[0].correct").value(true)).andExpect(jsonPath("$[0].points").value(2.5))
            .andExpect(jsonPath("$[0].explanation").value("Frozen author explanation"));
        mvc.perform(get("/exams/"+id+"/paper").header("Authorization",token(proctor,"TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/exams/"+id+"/paper").header("Authorization",token(student,"STUDENT"))).andExpect(status().isForbidden());
        assertEquals(1,audits.findByExamIdOrderByTimestampAsc(id).stream().filter(x->x.getAction().equals("PAPER_EXPORTED")).count());
    }
    @Test void unscheduledPublishKeepsReminderDefaultsAndDoesNotEmitDuplicateScheduleNotice()throws Exception {
        UUID id=create();
        assertEquals(List.of(1440,15),exams.findById(id).orElseThrow().getReminderOffsetsMinutes());
        core.transition(id,owner,Exam.Status.PUBLISHED);
        var types=outbox.findAll().stream().filter(event->event.getPayload().contains(id.toString())).map(com.datn.exam.entity.ExamOutboxEvent::getEventType).toList();
        assertEquals(List.of("exam.lifecycle.changed"),types);
    }

    @Test void resultPolicyDefaultsAndOnlyOwnerManagerCanUpdate()throws Exception {
        UUID id=create();
        mvc.perform(get("/exams/"+id+"/result-policy").header("Authorization",token(owner,"TEACHER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.autoRelease").value(true)).andExpect(jsonPath("$.showScore").value(true))
            .andExpect(jsonPath("$.showQuestionBreakdown").value(true)).andExpect(jsonPath("$.showFeedback").value(true))
            .andExpect(jsonPath("$.showCorrectAnswers").value(false)).andExpect(jsonPath("$.showExplanation").value(false));
        core.member(id,owner,manager,Exam.MemberRole.MANAGER,false);core.accept(id,manager,"TEACHER");
        core.member(id,owner,proctor,Exam.MemberRole.PROCTOR,false);core.accept(id,proctor,"TEACHER");
        String policy="{\"autoRelease\":false,\"showScore\":false,\"showQuestionBreakdown\":true,\"showFeedback\":false,\"showCorrectAnswers\":true,\"showExplanation\":false}";
        mvc.perform(put("/exams/"+id+"/result-policy").header("Authorization",token(manager,"TEACHER")).contentType("application/json").content(policy)).andExpect(status().isOk()).andExpect(jsonPath("$.autoRelease").value(false));
        mvc.perform(put("/exams/"+id+"/result-policy").header("Authorization",token(proctor,"TEACHER")).contentType("application/json").content(policy)).andExpect(status().isForbidden());
        mvc.perform(get("/exams/"+id+"/internal/result-policy").header("X-Service-Token","test-internal"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.examStatus").value("DRAFT")).andExpect(jsonPath("$.policy.showCorrectAnswers").value(true));
    }

    @Test void analyticsPermissionTokenIsRestrictedToManagePermission()throws Exception {
        UUID id=create();
        mvc.perform(get("/exams/"+id+"/internal/permission").param("actor",owner.toString()).param("manage","true").header("X-Service-Token","test-analytics-permission")).andExpect(status().isOk()).andExpect(content().string("OWNER"));
        mvc.perform(get("/exams/"+id+"/internal/permission").param("actor",owner.toString()).param("manage","false").header("X-Service-Token","test-analytics-permission")).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-analytics-permission")).andExpect(status().isUnauthorized());
    }

    @Test void scheduleUpdateUsesOffsetAndRevisionAndPublicPreviewWaitsForStartAt()throws Exception {
        UUID id=create();core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,10,5,2));
        String schedule="{\"startAt\":\"2030-01-01T17:00:00+07:00\",\"endAt\":\"2030-01-01T18:00:00+07:00\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"deadlineRule\":\"REQUIRE_FULL_WINDOW\",\"autoOpen\":false,\"autoClose\":true,\"reminderOffsetsMinutes\":[1440,15],\"expectedRevision\":0}";
        mvc.perform(put("/exams/"+id+"/schedule").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(schedule))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scheduleRevision").value(1))
            .andExpect(jsonPath("$.startAt").value("2030-01-01T10:00:00Z"))
            .andExpect(jsonPath("$.endAt").value("2030-01-01T11:00:00Z"));
        assertEquals(java.time.Instant.parse("2030-01-01T10:00:00Z"),exams.findById(id).orElseThrow().getStartAt());
        assertEquals(java.time.Instant.parse("2030-01-01T11:00:00Z"),exams.findById(id).orElseThrow().getEndAt());
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

    @Test void scheduleHttpValidatesOriginalOffsetIncludingDstGapAndOverlap()throws Exception {
        UUID invalidOffset=create();
        String mismatched="{\"startAt\":\"2030-01-01T17:00:00+08:00\",\"endAt\":\"2030-01-01T18:00:00+08:00\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"deadlineRule\":\"FULL_DURATION\",\"autoOpen\":false,\"autoClose\":false,\"reminderOffsetsMinutes\":[],\"expectedRevision\":0}";
        mvc.perform(put("/exams/"+invalidOffset+"/schedule").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(mismatched))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("startAt offset does not match timezone"));

        UUID gap=create();
        String dstGap="{\"startAt\":\"2030-03-10T02:30:00-08:00\",\"endAt\":\"2030-03-10T04:00:00-07:00\",\"timezone\":\"America/Los_Angeles\",\"deadlineRule\":\"FULL_DURATION\",\"autoOpen\":false,\"autoClose\":false,\"reminderOffsetsMinutes\":[],\"expectedRevision\":0}";
        mvc.perform(put("/exams/"+gap+"/schedule").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(dstGap))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("startAt falls in a daylight-saving gap"));

        UUID overlap=create();
        String dstOverlap="{\"startAt\":\"2030-11-03T01:30:00-07:00\",\"endAt\":\"2030-11-03T01:30:00-08:00\",\"timezone\":\"America/Los_Angeles\",\"deadlineRule\":\"FULL_DURATION\",\"autoOpen\":false,\"autoClose\":false,\"reminderOffsetsMinutes\":[],\"expectedRevision\":0}";
        mvc.perform(put("/exams/"+overlap+"/schedule").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(dstOverlap))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.startAt").value("2030-11-03T08:30:00Z"))
            .andExpect(jsonPath("$.endAt").value("2030-11-03T09:30:00Z"));
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
        UUID id=create();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("DRAFT",1,false)));
        assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.PUBLISHED));assertEquals(Exam.Status.DRAFT,exams.findById(id).orElseThrow().getStatus());
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("ACTIVE",1,true)));
        assertThrows(Exception.class,()->core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,200,101,1)));
        core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,1,1,1));core.transition(id,owner,Exam.Status.PUBLISHED);core.transition(id,owner,Exam.Status.OPEN);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();try{core.join(id,UUID.randomUUID(),null);return true;}catch(Exception e){return false;}}));gate.countDown();int accepted=0;for(var result:results)if(result.get())accepted++;assertEquals(1,accepted);}finally{pool.shutdownNow();}
    }
    @Test void bankAwareEligibilityUsesProviderDecisionAndPreservesSnapshot()throws Exception {
        for(String bankType:List.of("PERSONAL","PUBLIC","SYSTEM","ORGANIZATION")) {
            UUID id=create();var original=question("ACTIVE",3,true);
            String publicationStatus=bankType.equals("PERSONAL")?null:bankType.equals("ORGANIZATION")?"APPROVED":"PUBLISHED";
            var eligible=new QuestionBankClient.Question(qid,3L,"ACTIVE",original.questionType(),original.content(),original.options(),null,true,bankType,publicationStatus);
            when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(eligible));
            core.member(id,owner,manager,Exam.MemberRole.MANAGER,false);core.accept(id,manager,"TEACHER");
            core.transition(id,manager,Exam.Status.PUBLISHED);
            verify(bank,atLeastOnce()).eligible(eq(List.of(qid)),eq("test-qb"),eq(owner),eq(true),eq(true));
            var frozen=exams.findById(id).orElseThrow().getSnapshots().get(0);
            assertEquals(3,frozen.questionVersion());assertEquals(0,new BigDecimal("2.5").compareTo(frozen.points()));assertTrue(frozen.options().get(0).correct());
            when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(new QuestionBankClient.Question(qid,4L,"ARCHIVED",original.questionType(),"Changed provider",List.of(),null,false,bankType,"REJECTED")));
            var safe=mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-internal")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(3,json.readTree(safe).get(0).path("questionVersion").asInt());assertTrue(safe.contains("Original"));assertFalse(safe.contains("Changed provider"));assertFalse(safe.contains("correct"));
            assertEquals(frozen,exams.findById(id).orElseThrow().getSnapshots().get(0));
        }
    }
    @Test void falseOrMissingEligibilityFailsClosedEvenLegacyApproved()throws Exception {
        var active=question("ACTIVE",1,true);
        var legacy=new QuestionBankClient.Question(qid,1L,"APPROVED",active.questionType(),active.content(),active.options(),null);
        assertNull(legacy.eligible());
        var missing=json.valueToTree(question("APPROVED",1,true));((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("eligible");
        var deserialized=json.treeToValue(missing,QuestionBankClient.Question.class);assertNull(deserialized.eligible());
        for(var ineligible:List.of(question("ACTIVE",1,false),question("APPROVED",1,false),legacy,deserialized)) {
            UUID id=create();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(ineligible));
            var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->core.transition(id,owner,Exam.Status.PUBLISHED));
            assertEquals(409,error.getStatusCode().value());var e=exams.findById(id).orElseThrow();assertEquals(Exam.Status.DRAFT,e.getStatus());assertTrue(e.getSnapshots().isEmpty());
            assertTrue(audits.findByExamIdOrderByTimestampAsc(id).stream().noneMatch(a->a.getAction().equals("EXAM_PUBLISHED")));
        }
    }
    @Test void eligibleDoesNotBypassVersionOrGradingKeyValidation()throws Exception {
        var active=question("ACTIVE",1,true);
        for(var invalid:List.of(
            new QuestionBankClient.Question(qid,null,"ACTIVE",active.questionType(),active.content(),active.options(),null,true,"SYSTEM",null),
            new QuestionBankClient.Question(qid,0L,"ACTIVE",active.questionType(),active.content(),active.options(),null,true,"PUBLIC","PUBLISHED"),
            new QuestionBankClient.Question(qid,1L,"ACTIVE",active.questionType(),active.content(),List.of(new Snapshot.Option(oid,"No key",0,false),new Snapshot.Option(UUID.randomUUID(),"No key",1,false)),null,true,"PERSONAL",null))) {
            UUID id=create();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(invalid));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->core.transition(id,owner,Exam.Status.PUBLISHED));
            assertEquals(Exam.Status.DRAFT,exams.findById(id).orElseThrow().getStatus());
        }
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
