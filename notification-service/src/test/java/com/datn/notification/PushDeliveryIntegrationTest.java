package com.datn.notification;
import com.datn.notification.delivery.DeliveryWorker;
import com.datn.notification.scheduling.SchedulingJobs;
import com.datn.notification.service.NotificationService;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Push subscriptions API and per-subscription delivery against real PostgreSQL with loopback push-service stubs (test profile only). */
class PushDeliveryIntegrationTest extends IntegrationTestSupport {
    static final Instant BASE=Instant.parse("2026-10-06T00:00:00Z");
    @Autowired MockMvc mvc;@Autowired NotificationService service;@Autowired SchedulingJobs jobs;@Autowired DeliveryWorker deliveries;
    @Autowired MutableClock clock;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    UUID exam=UUID.randomUUID(),owner=UUID.randomUUID(),student=UUID.randomUUID(),other=UUID.randomUUID();

    @BeforeEach void reset(){
        db.execute("TRUNCATE scheduling_jobs, notification_deliveries, exam_schedule_state, push_subscriptions, notification_preferences");
        ROUTES.clear();REQUESTS.clear();clock.set(BASE);
        ROUTES.put("/auth/internal/contacts",r->new Response(200,"[]"));
    }
    String bearer(UUID user){
        return "Bearer "+Jwts.builder().subject(user.toString()).claim("type","access").claim("role","STUDENT").expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    String endpoint(String name){return stubUrl()+"/push/"+name;}
    UUID subscribe(UUID user,String name,PushTestKeys.Browser b)throws Exception{
        String body=json.writeValueAsString(Map.of("endpoint",endpoint(name),"expirationTime",0,"keys",Map.of("p256dh",b.p256dh(),"auth",b.authSecret())));
        var res=mvc.perform(post("/notifications/push/subscriptions").header("Authorization",bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(res).get("id").asText());
    }
    void pushRoute(String name,int... statuses){
        var calls=new AtomicInteger();
        ROUTES.put("/push/"+name,r->new Response(statuses[Math.min(calls.getAndIncrement(),statuses.length-1)],""));
    }
    List<IntegrationTestSupport.Request> pushCalls(String name){return REQUESTS.stream().filter(r->r.path().equals("/push/"+name)).toList();}
    /** Publishes a schedule event (Exam PUBLISHED) and fans it out to {@code student}. */
    UUID scheduleFor(long revision,Instant start,List<UUID> students)throws Exception{
        var recipients=new ArrayList<Map<String,Object>>();recipients.add(Map.of("userId",owner.toString(),"relation","OWNER","started",false,"submitted",false));
        students.forEach(s->recipients.add(Map.of("userId",s.toString(),"relation","ASSIGNED","started",false,"submitted",false)));
        var snap=new LinkedHashMap<String,Object>();
        snap.put("examId",exam.toString());snap.put("examTitle","Toán 12");snap.put("status","PUBLISHED");snap.put("scheduleRevision",revision);snap.put("lifecycleRevision",1);snap.put("recipientRevision",1);
        snap.put("startAt",start.toString());snap.put("endAt",null);snap.put("timezone","Asia/Ho_Chi_Minh");snap.put("deadlineRule","FULL_DURATION");snap.put("reminderOffsetsMinutes",List.of(15));snap.put("recipients",recipients);
        String body=json.writeValueAsString(snap);
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(200,body));
        var p=new LinkedHashMap<String,Object>();
        p.put("examId",exam.toString());p.put("examTitle","Toán 12");p.put("scheduleRevision",revision);p.put("changeType",revision==1?"SCHEDULED":"RESCHEDULED");p.put("startAt",start.toString());p.put("endAt",null);
        p.put("timezone","Asia/Ho_Chi_Minh");p.put("deadlineRule","FULL_DURATION");p.put("reminderOffsetsMinutes",List.of(15));p.put("examStatus","PUBLISHED");p.put("actorUserId",owner.toString());
        UUID id=UUID.randomUUID();
        service.onEvent(json.writeValueAsBytes(Map.of("eventId",id.toString(),"eventType","exam.schedule.changed","eventVersion",1,"occurredAt",BASE.toString(),"payload",p)));
        while(jobs.runDue()>0){}
        return id;
    }
    String pushStatus(UUID subscription){return db.queryForObject("SELECT status||':'||coalesce(skip_reason,'') FROM notification_deliveries WHERE subscription_id=? ORDER BY created_at DESC LIMIT 1",String.class,subscription);}
    void drain(){while(deliveries.runDue()>0){}}

    String prefs(UUID user,String body)throws Exception{
        var req=body==null?get("/notifications/preferences"):put("/notifications/preferences").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req.header("Authorization",bearer(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test void preferencesDefaultOnAreOwnerScopedAndValidated()throws Exception{
        var all=json.readTree(prefs(student,null)).get("settings");
        assertEquals(5,all.size());
        all.forEach(s->{assertTrue(s.get("email").asBoolean());assertTrue(s.get("push").asBoolean());});
        var after=json.readTree(prefs(student,"{\"settings\":[{\"category\":\"REMINDER\",\"email\":false,\"push\":true}]}")).get("settings");
        assertEquals(5,after.size(),"categories left out keep their value");
        after.forEach(s->assertEquals(!s.get("category").asText().equals("REMINDER"),s.get("email").asBoolean()));
        json.readTree(prefs(other,null)).get("settings").forEach(s->assertTrue(s.get("email").asBoolean(),"another user is unaffected"));
        for(String bad:List.of("{\"settings\":[]}","{\"settings\":[{\"category\":\"NOPE\",\"email\":false,\"push\":false}]}",
                "{\"settings\":[{\"category\":\"RESULT\",\"email\":false,\"push\":false},{\"category\":\"RESULT\",\"email\":true,\"push\":true}]}"))
            mvc.perform(put("/notifications/preferences").header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/notifications/preferences")).andExpect(status().isUnauthorized());
    }

    @Test void switchedOffChannelsAreSkippedButTheInboxStillGetsTheNotice()throws Exception{
        var b=PushTestKeys.browser();UUID sub=subscribe(student,"pref",b);pushRoute("pref",201);
        prefs(student,"{\"settings\":[{\"category\":\"SCHEDULE\",\"email\":false,\"push\":false}]}");
        scheduleFor(1,BASE.plus(Duration.ofDays(1)),List.of(student,other));
        var mine=db.queryForList("""
            SELECT d.channel||':'||d.status||':'||coalesce(d.skip_reason,'') FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id
            WHERE n.type='EXAM_SCHEDULED' AND d.recipient_user_id=? ORDER BY d.channel""",String.class,student);
        assertEquals(List.of("EMAIL:SKIPPED:USER_PREFERENCE","PUSH:SKIPPED:USER_PREFERENCE"),mine);
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM notifications WHERE type='EXAM_SCHEDULED' AND recipient_user_id=?",Long.class,student),"inbox always kept");
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM notification_deliveries WHERE recipient_user_id=? AND channel='EMAIL' AND status='PENDING'",Long.class,other));
        drain();
        assertTrue(pushCalls("pref").isEmpty());
    }

    @Test void turningAChannelOffStopsAlreadyQueuedDeliveries()throws Exception{
        var b=PushTestKeys.browser();UUID sub=subscribe(student,"late",b);pushRoute("late",201);
        scheduleFor(1,BASE.plus(Duration.ofDays(1)),List.of(student));
        assertEquals("PENDING:",pushStatus(sub));
        prefs(student,"{\"settings\":[{\"category\":\"SCHEDULE\",\"email\":true,\"push\":false}]}");
        drain();
        assertEquals("SKIPPED:USER_PREFERENCE",pushStatus(sub));
        assertTrue(pushCalls("late").isEmpty());
        assertNotEquals("USER_PREFERENCE",db.queryForObject("SELECT coalesce(skip_reason,'') FROM notification_deliveries WHERE recipient_user_id=? AND channel='EMAIL'",String.class,student),"email still on");
    }

    @Test void subscriptionApiIsOwnerScopedAndValidated()throws Exception{
        mvc.perform(get("/notifications/push/public-key").header("Authorization",bearer(student))).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.publicKey").value(PushTestKeys.b64(PushTestKeys.point((java.security.interfaces.ECPublicKey)VAPID.getPublic()))));
        mvc.perform(get("/notifications/push/public-key")).andExpect(status().isUnauthorized());
        var b=PushTestKeys.browser();UUID id=subscribe(student,"a",b);
        assertEquals(id,subscribe(student,"a",b),"re-registering the same endpoint is idempotent");
        mvc.perform(delete("/notifications/push/subscriptions/"+id).header("Authorization",bearer(other))).andExpect(status().isNotFound());
        assertNull(db.queryForObject("SELECT revoked_at FROM push_subscriptions WHERE id=?",Object.class,id),"another user cannot revoke");
        for(Object bad:List.of(Map.of("endpoint",endpoint("x"),"keys",Map.of("p256dh","AAAA","auth",b.authSecret())),
                Map.of("endpoint",endpoint("x"),"keys",Map.of("p256dh",b.p256dh(),"auth",PushTestKeys.b64(new byte[8]))),
                Map.of("endpoint","ftp://127.0.0.1/x","keys",Map.of("p256dh",b.p256dh(),"auth",b.authSecret())),
                Map.of("endpoint",endpoint("x")+"y".repeat(2100),"keys",Map.of("p256dh",b.p256dh(),"auth",b.authSecret())),
                Map.of("endpoint",endpoint("x"))))
            mvc.perform(post("/notifications/push/subscriptions").header("Authorization",bearer(student)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/notifications/push/subscriptions/"+id).header("Authorization",bearer(student))).andExpect(status().isNoContent());
        mvc.perform(delete("/notifications/push/subscriptions/"+id).header("Authorization",bearer(student))).andExpect(status().isNoContent());
        assertNotNull(db.queryForObject("SELECT revoked_at FROM push_subscriptions WHERE id=?",Object.class,id));
    }

    @Test void eachSubscriptionRetriedIndependentlyAndPayloadDecrypts()throws Exception{
        var a=PushTestKeys.browser();var b=PushTestKeys.browser();
        UUID subA=subscribe(student,"a",a),subB=subscribe(student,"b",b);
        pushRoute("a",201);pushRoute("b",500,201);
        scheduleFor(1,BASE.plus(Duration.ofDays(1)),List.of(student));
        drain();
        assertEquals("SENT:",pushStatus(subA));assertEquals("PENDING:",pushStatus(subB));
        assertEquals(500,db.queryForObject("SELECT provider_status FROM notification_deliveries WHERE subscription_id=?",Integer.class,subB));
        clock.advance(Duration.ofSeconds(30));drain();
        assertEquals("SENT:",pushStatus(subB));
        clock.advance(Duration.ofHours(1));drain();
        assertEquals(1,pushCalls("a").size(),"partial success never resends the successful endpoint");assertEquals(2,pushCalls("b").size());
        var call=pushCalls("a").getFirst();
        assertEquals("aes128gcm",call.header("content-encoding"));assertEquals("normal",call.header("urgency"));assertTrue(call.header("authorization").startsWith("vapid t="));
        assertNotNull(call.header("topic"));assertTrue(Long.parseLong(call.header("ttl"))<=24*3600);
        JsonNode push=json.readTree(PushTestKeys.decrypt(call.body(),a));
        assertEquals("Đã có lịch thi",push.get("title").asText());assertEquals("http://app.test/exam/lobby/"+exam,push.get("url").asText());
        assertTrue(push.get("body").asText().contains("GMT+07:00"));
        assertEquals(Set.of("notificationId","type","title","body","url","tag"),toSet(push.fieldNames()),"no other data in the push payload");
    }

    @Test void reminderPushIsUrgentAndExpiresAtStart()throws Exception{
        var a=PushTestKeys.browser();subscribe(student,"r",a);pushRoute("r",201);
        Instant start=BASE.plus(Duration.ofHours(1));
        scheduleFor(1,start,List.of(student));drain();
        clock.set(start.minus(Duration.ofMinutes(15)));while(jobs.runDue()>0){}drain();
        var reminder=pushCalls("r").getLast();
        assertEquals("high",reminder.header("urgency"));assertEquals(String.valueOf(Duration.ofMinutes(15).toSeconds()),reminder.header("ttl"));
        assertEquals("EXAM_REMINDER",json.readTree(PushTestKeys.decrypt(reminder.body(),a)).get("type").asText());
    }

    @Test void goneRevokesRedirectIsNotFollowedAndRejectionsAreFinal()throws Exception{
        UUID gone=subscribe(student,"gone",PushTestKeys.browser()),moved=subscribe(student,"moved",PushTestKeys.browser()),bad=subscribe(student,"bad",PushTestKeys.browser());
        pushRoute("gone",410);pushRoute("bad",400);
        ROUTES.put("/push/moved",r->new Response(303,"",Map.of("Location",endpoint("evil"))));
        scheduleFor(1,BASE.plus(Duration.ofDays(1)),List.of(student));drain();
        assertEquals("FAILED:SUBSCRIPTION_GONE",pushStatus(gone));
        assertNotNull(db.queryForObject("SELECT revoked_at FROM push_subscriptions WHERE id=?",Object.class,gone));
        assertEquals("FAILED:REJECTED",pushStatus(moved));assertEquals("FAILED:REJECTED",pushStatus(bad));
        assertTrue(pushCalls("evil").isEmpty(),"redirects are never followed");
        clock.advance(Duration.ofHours(2));drain();
        assertEquals(1,pushCalls("gone").size());assertEquals(1,pushCalls("bad").size());
        scheduleFor(2,BASE.plus(Duration.ofDays(2)),List.of(student));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM notification_deliveries WHERE subscription_id=? AND status='PENDING'",Long.class,gone),"revoked subscription gets no new deliveries");
    }

    @Test void reRegisteredEndpointNeverReceivesPreviousOwnersPushAndMissingPermissionIsSkipped()throws Exception{
        var shared=PushTestKeys.browser();UUID sub=subscribe(student,"shared",shared);pushRoute("shared",201);
        scheduleFor(1,BASE.plus(Duration.ofDays(1)),List.of(student,other));
        assertEquals("SKIPPED:NO_SUBSCRIPTION",db.queryForObject("""
            SELECT d.status||':'||d.skip_reason FROM notification_deliveries d WHERE d.recipient_user_id=? AND d.channel='PUSH'""",String.class,other));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM notification_deliveries WHERE recipient_user_id=? AND channel='EMAIL'",Long.class,other),
            "exactly the matrix email; missing push adds no fallback email");
        assertEquals(sub,subscribe(other,"shared",shared),"same browser endpoint now belongs to the other account");
        drain();
        assertEquals("SKIPPED:UNSUBSCRIBED",db.queryForObject("SELECT status||':'||skip_reason FROM notification_deliveries WHERE subscription_id=? AND recipient_user_id=?",String.class,sub,student));
        assertTrue(pushCalls("shared").isEmpty(),"previous owner's notification is never pushed to the new owner's browser");
    }

    @Test void rowsCrossingExpiryDuringABatchAreNeverDispatched()throws Exception{
        var a=PushTestKeys.browser();var b=PushTestKeys.browser();
        UUID subA=subscribe(student,"x1",a),subB=subscribe(student,"x2",b);
        pushRoute("x1",201);pushRoute("x2",201);
        Instant start=BASE.plus(Duration.ofHours(1));
        scheduleFor(1,start,List.of(student));drain();
        int before=pushCalls("x1").size()+pushCalls("x2").size();
        // The reminder is fanned out 15 minutes before start; the first push call takes the clock past startAt.
        clock.set(start.minus(Duration.ofMinutes(15)));while(jobs.runDue()>0){}
        for(String n:List.of("x1","x2"))ROUTES.put("/push/"+n,r->{clock.set(start.plusSeconds(1));return new Response(201,"");});
        drain();
        assertEquals(before+1,pushCalls("x1").size()+pushCalls("x2").size(),"only the row checked before startAt is dispatched");
        var statuses=db.queryForList("""
            SELECT d.status FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id
            WHERE n.type='EXAM_REMINDER' AND d.channel='PUSH' AND d.recipient_user_id=? ORDER BY d.status""",String.class,student);
        assertEquals(List.of("EXPIRED","SENT"),statuses);
        assertEquals("expired before dispatch",db.queryForObject("SELECT d.last_error FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.type='EXAM_REMINDER' AND d.status='EXPIRED' AND d.recipient_user_id=?",String.class,student));
    }

    @Test void emailRowExpiringWhileContactsLoadIsNotSent()throws Exception{
        Instant start=BASE.plus(Duration.ofHours(1));
        scheduleFor(1,start,List.of(student));
        db.update("UPDATE notification_deliveries SET status='SKIPPED' WHERE status='PENDING'");
        clock.set(start.minus(Duration.ofMinutes(15)));while(jobs.runDue()>0){}
        ROUTES.put("/auth/internal/contacts",r->{clock.set(start.plusSeconds(1));
            return new Response(200,"[{\"userId\":\""+student+"\",\"email\":\"late@example.test\",\"displayName\":\"S\"}]");});
        drain();
        assertEquals("EXPIRED",db.queryForObject("SELECT d.status FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.type='EXAM_REMINDER' AND d.channel='EMAIL' AND d.recipient_user_id=?",String.class,student));
        assertNull(db.queryForObject("SELECT d.sent_at FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.type='EXAM_REMINDER' AND d.channel='EMAIL' AND d.recipient_user_id=?",java.sql.Timestamp.class,student));
    }

    @Test void concurrentWorkersSendEachDeliveryOnce()throws Exception{
        pushRoute("c",201);subscribe(student,"c",PushTestKeys.browser());
        for(int r=1;r<=20;r++)scheduleFor(r,BASE.plus(Duration.ofDays(1)).plusSeconds(r),List.of(student));
        assertEquals(20L,db.queryForObject("SELECT count(*) FROM notification_deliveries WHERE channel='PUSH' AND status='PENDING'",Long.class));
        var pool=Executors.newFixedThreadPool(4);
        try{var all=new ArrayList<Future<?>>();for(int i=0;i<4;i++)all.add(pool.submit(this::drain));for(var f:all)f.get(60,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(20,pushCalls("c").size());
        assertEquals(20L,db.queryForObject("SELECT count(*) FROM notification_deliveries WHERE channel='PUSH' AND status='SENT'",Long.class));
    }
    static Set<String> toSet(Iterator<String> i){var s=new HashSet<String>();i.forEachRemaining(s::add);return s;}
}
