package com.datn.notification;
import com.datn.notification.config.RabbitMQConfig;
import com.datn.notification.delivery.DeliveryWorker;
import com.datn.notification.grading.GradingNotifications;
import com.datn.notification.scheduling.SchedulingJobs;
import com.datn.notification.service.NotificationService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Manual-grading notices against real PostgreSQL, RabbitMQ and SMTP (Mailpit); Exam/Auth/push services are loopback stubs. */
class GradingNotificationIntegrationTest extends IntegrationTestSupport {
    static final Instant BASE=Instant.parse("2026-10-07T00:00:00Z");
    @Autowired NotificationService service;@Autowired SchedulingJobs jobs;@Autowired DeliveryWorker deliveries;@Autowired MutableClock clock;
    @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired RabbitTemplate rabbit;
    UUID exam=UUID.randomUUID(),session=UUID.randomUUID(),result=UUID.randomUUID(),owner=UUID.randomUUID(),manager=UUID.randomUUID(),proctorLike=UUID.randomUUID(),
        student=UUID.randomUUID(),joined=UUID.randomUUID(),follower=UUID.randomUUID();

    @BeforeEach void reset(){
        db.execute("TRUNCATE scheduling_jobs, notification_deliveries, exam_schedule_state, push_subscriptions, grading_notice_state");
        ROUTES.clear();REQUESTS.clear();clock.set(BASE);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------
    Map<String,Object> recipient(UUID u,String relation){return Map.of("userId",u.toString(),"relation",relation,"started",true,"submitted",true);}
    void snapshot(List<Map<String,Object>> recipients){
        var s=new LinkedHashMap<String,Object>();
        s.put("examId",exam.toString());s.put("examTitle","Toán 12");s.put("status","CLOSED");s.put("scheduleRevision",1);s.put("lifecycleRevision",1);s.put("recipientRevision",1);
        s.put("startAt",null);s.put("endAt",null);s.put("timezone","Asia/Ho_Chi_Minh");s.put("deadlineRule","FULL_DURATION");s.put("reminderOffsetsMinutes",List.of());s.put("recipients",recipients);
        String body;try{body=json.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException(e);}
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(200,body));
    }
    List<Map<String,Object>> examPeople(){return List.of(recipient(owner,"OWNER"),recipient(manager,"MANAGER"),recipient(student,"ASSIGNED"),recipient(joined,"JOINED"),recipient(follower,"FOLLOWER"));}
    Map<String,Object> reviewPayload(long revision){
        return new LinkedHashMap<>(Map.of("resultId",result.toString(),"sessionId",session.toString(),"examId",exam.toString(),"examTitle","Toán 12","resultRevision",revision));
    }
    Map<String,Object> studentPayload(long revision){
        return new LinkedHashMap<>(Map.of("sessionId",session.toString(),"examId",exam.toString(),"examTitle","Toán 12","studentUserId",student.toString(),"resultRevision",revision));
    }
    Map<String,Object> releasedPayload(Long revision){
        var p=studentPayload(1);p.remove("resultRevision");p.put("gradingMode","MANUAL");
        if(revision!=null){p.put("resultId",result.toString());p.put("resultRevision",revision);}
        return p;
    }
    UUID deliver(String type,Map<String,Object> payload)throws Exception{
        UUID id=UUID.randomUUID();
        service.onEvent(json.writeValueAsBytes(Map.of("eventId",id.toString(),"eventType",type,"eventVersion",1,"occurredAt",clock.instant().toString(),"payload",payload)));
        return id;
    }
    void drainJobs(){while(jobs.runDue()>0){}}
    void drainDeliveries(){while(deliveries.runDue()>0){}}
    long noticesFor(UUID user,String type){return db.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id=? AND type=? AND entity_id IN (?,?)",Long.class,user,type,session,exam);}
    Map<String,Object> notice(UUID user,String type){return db.queryForMap("SELECT id,title,message,entity_type,entity_id,metadata::text AS metadata FROM notifications WHERE recipient_user_id=? AND type=?",user,type);}
    List<String> deliveriesOf(UUID user,String type){
        return db.queryForList("""
            SELECT d.channel||':'||d.status||':'||coalesce(d.skip_reason,'') FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id
            WHERE n.recipient_user_id=? AND n.type=? ORDER BY 1""",String.class,user,type);
    }
    Set<UUID> reviewRecipients(){
        return new HashSet<>(db.queryForList("SELECT recipient_user_id FROM notifications WHERE type='GRADING_REVIEW_REQUIRED' AND entity_id=?",UUID.class,exam));
    }
    String contactsFor(UUID... users){
        var out=new StringBuilder("[");for(UUID u:users)out.append(out.length()>1?",":"").append("{\"userId\":\"").append(u).append("\",\"email\":\"u-").append(u.toString(),0,8).append("@example.test\",\"displayName\":\"U\"}");
        return out.append("]").toString();
    }
    JsonNode mailpit(String path)throws Exception{
        String host=Optional.ofNullable(System.getenv("TEST_SMTP_HOST")).orElse("localhost");
        var res=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+host+":8025"+path)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,res.statusCode());return json.readTree(res.body());
    }

    // ---- first release ------------------------------------------------------------------------------------------
    @Test void firstReleaseWithoutRevisionKeepsLegacyBehaviourAndAddsChannels()throws Exception{
        var payload=releasedPayload(null);payload.put("score",987654.321);payload.put("answers","SECRET-ANSWER");
        UUID event=deliver("grading.result.released",payload);
        var n=notice(student,"RESULT_AVAILABLE");
        assertEquals("EXAM_SESSION",n.get("entity_type"));assertEquals(session,n.get("entity_id"));assertEquals("Đã có kết quả thi",n.get("title"));
        assertTrue(n.get("message").toString().startsWith("Giáo viên đã chấm xong bài kỳ thi \"Toán 12\""));
        JsonNode meta=json.readTree(n.get("metadata").toString());
        assertEquals("/exam/result/"+session,meta.get("link").asText());assertEquals("RESULT_OWNER",meta.get("audience").asText());
        assertFalse(n.toString().contains("987654") || n.toString().contains("SECRET-ANSWER"),"score/answers never stored");
        assertEquals(List.of("EMAIL:PENDING:","PUSH:SKIPPED:NO_SUBSCRIPTION"),deliveriesOf(student,"RESULT_AVAILABLE"),"no subscription: skipped, never an email fallback or fake SENT");
        // Same event redelivered by the broker, then a fresh eventId with no revision (legacy dedup is by eventId only).
        service.onEvent(json.writeValueAsBytes(Map.of("eventId",event.toString(),"eventType","grading.result.released","eventVersion",1,"occurredAt",clock.instant().toString(),"payload",payload)));
        assertEquals(1,noticesFor(student,"RESULT_AVAILABLE"));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM grading_notice_state WHERE session_id=?",Long.class,session),"legacy payload keeps no revision state");
    }

    @Test void releasedRevisionDeduplicatesAcrossEventIdsAndSkipsStaleOnes()throws Exception{
        deliver("grading.result.released",releasedPayload(2L));
        deliver("grading.result.released",releasedPayload(2L));
        deliver("grading.result.released",releasedPayload(1L));
        assertEquals(1,noticesFor(student,"RESULT_AVAILABLE"));
        assertEquals(2L,db.queryForObject("SELECT released_revision FROM grading_notice_state WHERE session_id=?",Long.class,session));
        assertEquals(2,db.queryForList("SELECT 1 FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.recipient_user_id=?",student).size(),"one email row + one push row");
        assertEquals(GradingNotifications.key("grading-released",session,2),db.queryForObject("SELECT source_event_id FROM notifications WHERE recipient_user_id=?",UUID.class,student));
    }

    // ---- regrade ------------------------------------------------------------------------------------------------
    @Test void regradeNoticeGoesToTheStudentWithoutScoresAndIsRevisionDeduplicated()throws Exception{
        var payload=studentPayload(2);payload.put("score",12.5);payload.put("feedback","private teacher note");payload.put("reason","audit reason");
        deliver("grading.result.regraded",payload);deliver("grading.result.regraded",payload);deliver("grading.result.regraded",studentPayload(1));
        assertEquals(1,noticesFor(student,"RESULT_REGRADED"));
        var n=notice(student,"RESULT_REGRADED");
        String all=n.toString().toLowerCase();
        for(String forbidden:List.of("12.5","private teacher note","audit reason","điểm số"))assertFalse(all.contains(forbidden.toLowerCase()),forbidden);
        assertEquals("Kết quả thi đã được cập nhật",n.get("title"));
        assertEquals("/exam/result/"+session,json.readTree(n.get("metadata").toString()).get("link").asText());
        assertEquals(List.of("EMAIL:PENDING:","PUSH:SKIPPED:NO_SUBSCRIPTION"),deliveriesOf(student,"RESULT_REGRADED"));
        for(UUID other:List.of(owner,manager,joined,follower))assertEquals(0,noticesFor(other,"RESULT_REGRADED"),"only the student owning the result is notified");
        deliver("grading.result.regraded",studentPayload(3));
        assertEquals(2,noticesFor(student,"RESULT_REGRADED"),"a later revision is a new logical notice");
    }

    @Test void regradeOvertakingTheFirstReleaseDoesNotLoseIt()throws Exception{
        deliver("grading.result.regraded",studentPayload(2));
        deliver("grading.result.released",releasedPayload(1L));
        assertEquals(1,noticesFor(student,"RESULT_REGRADED"));assertEquals(1,noticesFor(student,"RESULT_AVAILABLE"),"the first release survives a newer regrade event");
        deliver("grading.result.released",releasedPayload(1L));deliver("grading.result.regraded",studentPayload(2));
        assertEquals(1,noticesFor(student,"RESULT_REGRADED"));assertEquals(1,noticesFor(student,"RESULT_AVAILABLE"));
    }

    // ---- review required ----------------------------------------------------------------------------------------
    @Test void reviewRequiredFansOutToOwnerAndManagersOnlyFromTheCurrentSnapshot()throws Exception{
        snapshot(examPeople());
        deliver("grading.review.required",reviewPayload(1));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM scheduling_jobs WHERE kind='GRADING_REVIEW' AND status='PENDING'",Long.class));
        drainJobs();
        assertEquals(Set.of(owner,manager),reviewRecipients(),"OWNER/MANAGER only: no student, follower or other relation");
        for(UUID teacher:List.of(owner,manager)){
            var n=notice(teacher,"GRADING_REVIEW_REQUIRED");
            assertEquals("Có bài làm cần chấm",n.get("title"));assertEquals("EXAM",n.get("entity_type"));assertEquals(exam,n.get("entity_id"));
            JsonNode meta=json.readTree(n.get("metadata").toString());
            assertEquals("/grading?examId="+exam,meta.get("link").asText());assertEquals("MANAGER",meta.get("audience").asText());assertEquals(session.toString(),meta.get("sessionId").asText());
            assertEquals(List.of("EMAIL:PENDING:","PUSH:SKIPPED:NO_SUBSCRIPTION"),deliveriesOf(teacher,"GRADING_REVIEW_REQUIRED"));
            assertFalse(n.toString().contains(student.toString()),"no student identity in the teacher notice");
        }
        assertEquals("DONE",db.queryForObject("SELECT status FROM scheduling_jobs WHERE kind='GRADING_REVIEW'",String.class));
        // Duplicate delivery (new eventId), older revision: no second job. A newer revision is a new logical notice.
        deliver("grading.review.required",reviewPayload(1));deliver("grading.review.required",reviewPayload(2));deliver("grading.review.required",reviewPayload(1));
        drainJobs();
        assertEquals(2,noticesFor(owner,"GRADING_REVIEW_REQUIRED"));assertEquals(2,noticesFor(manager,"GRADING_REVIEW_REQUIRED"));
        assertEquals(2L,db.queryForObject("SELECT review_revision FROM grading_notice_state WHERE session_id=?",Long.class,session));
    }

    @Test void teacherWhoLostMembershipIsNotNotifiedAndLateRevocationSkipsQueuedDelivery()throws Exception{
        snapshot(List.of(recipient(owner,"OWNER")));
        deliver("grading.review.required",reviewPayload(1));drainJobs();
        assertEquals(Set.of(owner),reviewRecipients(),"manager revoked before fan-out is not in the snapshot");
        // Fan-out with both teachers, then the manager is removed before the email is sent.
        session=UUID.randomUUID();snapshot(examPeople());
        deliver("grading.review.required",reviewPayload(1));drainJobs();
        assertEquals(1,noticesFor(manager,"GRADING_REVIEW_REQUIRED"));
        snapshot(List.of(recipient(owner,"OWNER")));
        ROUTES.put("/auth/internal/contacts",r->new Response(200,contactsFor(owner,manager)));
        drainDeliveries();
        var emails=db.queryForList("""
            SELECT n.recipient_user_id||'='||d.status||':'||coalesce(d.skip_reason,'') FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id
            WHERE d.channel='EMAIL' AND n.type='GRADING_REVIEW_REQUIRED' AND n.entity_id=? AND n.metadata->>'sessionId'=? ORDER BY 1""",String.class,exam,session.toString());
        assertEquals(new HashSet<>(List.of(owner+"=SENT:",manager+"=SKIPPED:RECIPIENT_REMOVED")),new HashSet<>(emails));
    }

    @Test void unknownRelationsFailClosedAndAMissingExamCancelsTheJob()throws Exception{
        snapshot(List.of(recipient(owner,"OWNER"),Map.of("userId",proctorLike.toString(),"relation","PROCTOR","started",false,"submitted",false)));
        deliver("grading.review.required",reviewPayload(1));drainJobs();
        var job=db.queryForMap("SELECT status,last_error FROM scheduling_jobs WHERE kind='GRADING_REVIEW'");
        assertEquals("PENDING",job.get("status"));assertEquals("Exam recipients response invalid",job.get("last_error"));
        assertEquals(0,reviewRecipients().size(),"an unverifiable snapshot notifies nobody");
        ROUTES.remove("/exams/"+exam+"/internal/notification-recipients");
        clock.advance(Duration.ofMinutes(5));drainJobs();
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM scheduling_jobs WHERE kind='GRADING_REVIEW'",String.class));
    }

    @Test void examProviderFailuresRetryWithBackoffAndExpire()throws Exception{
        deliver("grading.review.required",reviewPayload(1));
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(503,"{}"));drainJobs();
        var job=db.queryForMap("SELECT attempts,last_error,next_attempt_at FROM scheduling_jobs WHERE kind='GRADING_REVIEW'");
        assertEquals(1,job.get("attempts"));assertTrue(job.get("last_error").toString().contains("unavailable"));
        assertEquals(BASE.plusSeconds(30),((Timestamp)job.get("next_attempt_at")).toInstant());
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(401,"{}"));clock.advance(Duration.ofSeconds(30));drainJobs();
        assertTrue(db.queryForObject("SELECT last_error FROM scheduling_jobs WHERE kind='GRADING_REVIEW'",String.class).contains("credential"));
        clock.advance(Duration.ofHours(25));drainJobs();
        assertEquals("FAILED",db.queryForObject("SELECT status FROM scheduling_jobs WHERE kind='GRADING_REVIEW'",String.class),"expired before it could be verified: nothing was sent");
        assertEquals(0,reviewRecipients().size());
    }

    // ---- channels -----------------------------------------------------------------------------------------------
    @Test void studentResultEmailIsSentThroughSmtpWithoutAnExamLookup()throws Exception{
        // The student is NOT in any Exam snapshot (e.g. unassigned later): their own published result still reaches them.
        ROUTES.put("/auth/internal/contacts",r->new Response(200,contactsFor(student)));
        deliver("grading.result.regraded",studentPayload(2));
        drainDeliveries();
        assertEquals(List.of("EMAIL:SENT:","PUSH:SKIPPED:NO_SUBSCRIPTION"),deliveriesOf(student,"RESULT_REGRADED"));
        assertTrue(REQUESTS.stream().noneMatch(r->r.path().startsWith("/exams/")),"no Exam recheck for a student's own result");
        var call=REQUESTS.stream().filter(r->r.path().equals("/auth/internal/contacts")).findFirst().orElseThrow();
        assertEquals("test-contact-token",call.header("x-service-token"));assertNull(call.header("authorization"));
        String to="u-"+student.toString().substring(0,8)+"@example.test";
        JsonNode found=mailpit("/api/v1/search?query="+URLEncoder.encode("to:\""+to+"\"",StandardCharsets.UTF_8));
        assertEquals(1,found.get("messages_count").asInt());
        JsonNode msg=mailpit("/api/v1/message/"+found.get("messages").get(0).get("ID").asText());
        assertEquals("[EXAMINI] Kết quả thi đã được cập nhật",msg.get("Subject").asText());
        String text=msg.get("Text").asText();
        assertTrue(text.contains("http://app.test/exam/result/"+session),text);
        for(String forbidden:List.of("điểm","score","đáp án","feedback"))assertFalse(text.toLowerCase().contains(forbidden),forbidden);
        drainDeliveries();
        assertEquals(1,mailpit("/api/v1/search?query="+URLEncoder.encode("to:\""+to+"\"",StandardCharsets.UTF_8)).get("messages_count").asInt(),"a SENT row is not resent");
    }

    @Test void studentPushIsEncryptedForTheirBrowserAndCarriesTheResultLink()throws Exception{
        var browser=PushTestKeys.browser();UUID sub=UUID.randomUUID();
        db.update("INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth_secret) VALUES(?,?,?,?,?)",sub,student,stubUrl()+"/push/student",browser.p256dh(),browser.authSecret());
        ROUTES.put("/push/student",r->new Response(201,""));
        deliver("grading.result.released",releasedPayload(1L));
        drainDeliveries();
        assertEquals(List.of("EMAIL:PENDING:","PUSH:SENT:"),deliveriesOf(student,"RESULT_AVAILABLE"),"email waits for Auth contacts (unavailable in this test) but push is delivered");
        var call=REQUESTS.stream().filter(r->r.path().equals("/push/student")).findFirst().orElseThrow();
        JsonNode push=json.readTree(PushTestKeys.decrypt(call.body(),browser));
        assertEquals("RESULT_AVAILABLE",push.get("type").asText());assertEquals("http://app.test/exam/result/"+session,push.get("url").asText());
        assertEquals(Set.of("notificationId","type","title","body","url","tag"),new HashSet<>(toList(push.fieldNames())));
        assertFalse(push.toString().toLowerCase().contains("điểm"));
    }

    @Test void emailProviderOutageStaysObservableNotSent()throws Exception{
        ROUTES.put("/auth/internal/contacts",r->new Response(401,"{}"));
        deliver("grading.result.regraded",studentPayload(2));
        drainDeliveries();
        var d=db.queryForMap("""
            SELECT d.status,d.attempts,d.last_error FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.recipient_user_id=? AND d.channel='EMAIL'""",student);
        assertEquals("PENDING",d.get("status"));assertEquals(1,d.get("attempts"));assertTrue(d.get("last_error").toString().contains("credential"),d.toString());
        clock.advance(Duration.ofHours(25));drainDeliveries();
        assertEquals("EXPIRED",db.queryForObject("SELECT d.status FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.recipient_user_id=? AND d.channel='EMAIL'",String.class,student));
        assertEquals(1,noticesFor(student,"RESULT_REGRADED"),"the in-app notice remains even though email never left");
    }

    // ---- envelope security through the real broker --------------------------------------------------------------
    @Test void malformedGradingEventsAreRejectedAndDeadLettered()throws Exception{
        var bad=reviewPayload(1);bad.put("resultRevision",1.5);
        var e=Map.of("eventId",UUID.randomUUID().toString(),"eventType","grading.review.required","eventVersion",1,"occurredAt",BASE.toString(),"payload",bad);
        assertThrows(AmqpRejectAndDontRequeueException.class,()->service.onEvent(json.writeValueAsBytes(e)));
        while(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)!=null){}
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        var noRevision=studentPayload(1);noRevision.remove("resultRevision");
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,"grading.result.regraded",new Message(json.writeValueAsBytes(Map.of("eventId",UUID.randomUUID().toString(),"eventType","grading.result.regraded",
            "eventVersion",1,"occurredAt",BASE.toString(),"payload",noRevision)),props));
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,"grading.review.required",new Message(json.writeValueAsBytes(e),props));
        int dead=0;for(int i=0;i<2;i++)if(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE,10000)!=null)dead++;
        assertEquals(2,dead);
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM scheduling_jobs WHERE kind='GRADING_REVIEW'",Long.class));
        assertEquals(0,noticesFor(student,"RESULT_REGRADED"));
    }

    @Test void validGradingEventsArriveThroughTheRealBinding()throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,"grading.result.regraded",new Message(json.writeValueAsBytes(Map.of("eventId",UUID.randomUUID().toString(),"eventType","grading.result.regraded",
            "eventVersion",1,"occurredAt",BASE.toString(),"payload",studentPayload(1))),props));
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,"grading.review.required",new Message(json.writeValueAsBytes(Map.of("eventId",UUID.randomUUID().toString(),"eventType","grading.review.required",
            "eventVersion",1,"occurredAt",BASE.toString(),"payload",reviewPayload(1))),props));
        for(int i=0;i<100 && (noticesFor(student,"RESULT_REGRADED")<1 || db.queryForObject("SELECT count(*) FROM scheduling_jobs WHERE kind='GRADING_REVIEW'",Long.class)<1);i++)Thread.sleep(100);
        assertEquals(1,noticesFor(student,"RESULT_REGRADED"));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM scheduling_jobs WHERE kind='GRADING_REVIEW' AND status='PENDING'",Long.class));
    }
    static List<String> toList(Iterator<String> i){var l=new ArrayList<String>();i.forEachRemaining(l::add);return l;}
}
