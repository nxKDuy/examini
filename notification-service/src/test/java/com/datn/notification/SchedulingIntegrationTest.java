package com.datn.notification;
import com.datn.notification.client.*;
import com.datn.notification.config.RabbitMQConfig;
import com.datn.notification.delivery.DeliveryWorker;
import com.datn.notification.scheduling.*;
import com.datn.notification.service.NotificationService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Scheduling consumer, durable jobs and email delivery against real PostgreSQL, RabbitMQ and SMTP (Mailpit). */
class SchedulingIntegrationTest extends IntegrationTestSupport {
    static final Instant BASE=Instant.parse("2026-10-06T00:00:00Z");
    @Autowired NotificationService service;@Autowired SchedulingJobs jobs;@Autowired DeliveryWorker deliveries;@Autowired MutableClock clock;
    @Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired RabbitTemplate rabbit;
    List<Integer> snapOffsets=List.of();
    UUID exam=UUID.randomUUID(),owner=UUID.randomUUID(),manager=UUID.randomUUID(),manager2=UUID.randomUUID(),student=UUID.randomUUID(),follower=UUID.randomUUID(),done=UUID.randomUUID();

    @BeforeEach void reset(){
        db.execute("TRUNCATE scheduling_jobs, notification_deliveries, exam_schedule_state");
        ROUTES.clear();REQUESTS.clear();clock.set(BASE);
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------
    Map<String,Object> recipient(UUID u,String relation,boolean submitted){return Map.of("userId",u.toString(),"relation",relation,"started",submitted,"submitted",submitted);}
    List<Map<String,Object>> everyone(){return List.of(recipient(owner,"OWNER",false),recipient(manager,"MANAGER",false),recipient(manager2,"MANAGER",false),
        recipient(manager2,"FOLLOWER",false),recipient(student,"ASSIGNED",false),recipient(follower,"FOLLOWER",false),recipient(done,"JOINED",true));}
    void snapshot(String status,long scheduleRevision,Instant start,List<Map<String,Object>> recipients){
        var s=new LinkedHashMap<String,Object>();
        s.put("examId",exam.toString());s.put("examTitle","Toán 12");s.put("status",status);s.put("scheduleRevision",scheduleRevision);s.put("lifecycleRevision",1);s.put("recipientRevision",1);
        s.put("startAt",start==null?null:start.toString());s.put("endAt",start==null?null:start.plus(Duration.ofHours(2)).toString());s.put("timezone","Asia/Ho_Chi_Minh");
        s.put("deadlineRule","FULL_DURATION");s.put("reminderOffsetsMinutes",snapOffsets);s.put("recipients",recipients);
        String body;try{body=json.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException(e);}
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(200,body));
    }
    Map<String,Object> envelope(UUID id,String type,Map<String,Object> payload){
        return Map.of("eventId",id.toString(),"eventType",type,"eventVersion",1,"occurredAt",clock.instant().toString(),"payload",payload);
    }
    Map<String,Object> schedulePayload(long revision,String change,Instant start,List<Integer> offsets,String status,UUID actor){
        var p=new LinkedHashMap<String,Object>();
        p.put("examId",exam.toString());p.put("examTitle","Toán 12");p.put("scheduleRevision",revision);p.put("changeType",change);
        p.put("startAt",start==null?null:start.toString());p.put("endAt",start==null?null:start.plus(Duration.ofHours(2)).toString());p.put("timezone","Asia/Ho_Chi_Minh");
        p.put("deadlineRule","FULL_DURATION");p.put("reminderOffsetsMinutes",offsets);p.put("examStatus",status);p.put("actorUserId",actor==null?null:actor.toString());
        return p;
    }
    Map<String,Object> lifecyclePayload(long revision,String status,UUID actor){
        var p=new LinkedHashMap<String,Object>();
        p.put("examId",exam.toString());p.put("examTitle","Toán 12");p.put("status",status);p.put("trigger",actor==null?"AUTO":"MANUAL");p.put("actorUserId",actor==null?null:actor.toString());p.put("lifecycleRevision",revision);
        return p;
    }
    UUID deliver(String type,Map<String,Object> payload)throws Exception{UUID id=UUID.randomUUID();service.onEvent(json.writeValueAsBytes(envelope(id,type,payload)));return id;}
    UUID schedule(long revision,String change,Instant start,List<Integer> offsets,String status,UUID actor)throws Exception{return deliver("exam.schedule.changed",schedulePayload(revision,change,start,offsets,status,actor));}
    UUID lifecycle(long revision,String status,UUID actor)throws Exception{return deliver("exam.lifecycle.changed",lifecyclePayload(revision,status,actor));}
    void drainJobs(){while(jobs.runDue()>0){}}

    Set<UUID> recipientsOf(UUID source){return new HashSet<>(db.queryForList("SELECT recipient_user_id FROM notifications WHERE source_event_id=?",UUID.class,source));}
    long notificationsFor(UUID user,String type){return db.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id=? AND type=?",Long.class,user,type);}
    List<String> reminderStatuses(Instant start,int offset){
        return db.queryForList("SELECT status FROM scheduling_jobs WHERE kind='REMINDER' AND source_id=? ORDER BY created_at",String.class,SchedulingConsumer.reminderKey(exam,start,offset));
    }
    long pending(String kind){return db.queryForObject("SELECT count(*) FROM scheduling_jobs WHERE exam_id=? AND kind=? AND status='PENDING'",Long.class,exam,kind);}

    // ---- tests --------------------------------------------------------------------------------------------------
    @Test void brokerScheduleDraftReachesManagersOnlyThenPublishReachesStudents()throws Exception{
        Instant start=BASE.plus(Duration.ofDays(2));UUID eventId=UUID.randomUUID();
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,"exam.schedule.changed",new Message(json.writeValueAsBytes(envelope(eventId,"exam.schedule.changed",
            schedulePayload(1,"SCHEDULED",start,List.of(15,1440),"DRAFT",owner))),props));
        for(int i=0;i<100 && pending("REMINDER")<2;i++)Thread.sleep(100);
        assertEquals(1,pending("SCHEDULE"));assertEquals(2,pending("REMINDER"));
        snapshot("DRAFT",1,start,everyone());drainJobs();
        // DRAFT: managers only, actor excluded, manager2 listed twice still gets one.
        assertEquals(Set.of(manager,manager2),recipientsOf(eventId));
        var n=db.queryForMap("SELECT type,message,metadata->>'link' AS link FROM notifications WHERE source_event_id=? AND recipient_user_id=?",eventId,manager);
        assertEquals("EXAM_SCHEDULED",n.get("type"));assertEquals("/exams/"+exam,n.get("link"));
        assertTrue(n.get("message").toString().contains("07:00 08/10/2026 (GMT+07:00, Asia/Ho_Chi_Minh)"),n.get("message").toString());

        snapshot("PUBLISHED",1,start,everyone());lifecycle(1,"PUBLISHED",owner);drainJobs();
        UUID published=SchedulingConsumer.studentScheduleKey(exam,1);
        assertEquals(Set.of(student,follower),recipientsOf(published));
        assertEquals("/exam/lobby/"+exam,db.queryForObject("SELECT metadata->>'link' FROM notifications WHERE source_event_id=? AND recipient_user_id=?",String.class,published,student));
        var channels=db.queryForList("SELECT channel||':'||status||':'||coalesce(skip_reason,'') FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.source_event_id=? AND n.recipient_user_id=? ORDER BY 1",String.class,published,student);
        assertEquals(List.of("EMAIL:PENDING:","PUSH:SKIPPED:NO_SUBSCRIPTION"),channels);
    }

    @Autowired org.springframework.context.ApplicationContext context;
    @Test void smtpStateDoesNotDriveServiceHealth(){
        assertFalse(context.containsBean("mailHealthContributor"),"SMTP outage or missing config must not make /actuator/health DOWN");
    }

    @Test void staleOutOfOrderAndDuplicateEventsSkipped()throws Exception{
        Instant start=BASE.plus(Duration.ofDays(1));
        var e2=envelope(UUID.randomUUID(),"exam.schedule.changed",schedulePayload(2,"RESCHEDULED",start,List.of(15),"PUBLISHED",owner));
        service.onEvent(json.writeValueAsBytes(e2));
        schedule(1,"SCHEDULED",start.plusSeconds(3600),List.of(15),"PUBLISHED",owner);
        service.onEvent(json.writeValueAsBytes(e2));
        schedule(2,"RESCHEDULED",start.plusSeconds(7200),List.of(15),"PUBLISHED",owner);
        assertEquals(1,pending("SCHEDULE"));
        assertEquals(2L,db.queryForObject("SELECT schedule_revision FROM exam_schedule_state WHERE exam_id=?",Long.class,exam));
        assertEquals(List.of("PENDING"),reminderStatuses(start,15));
        lifecycle(3,"OPEN",null);lifecycle(2,"CLOSED",owner);
        assertEquals("OPEN",db.queryForObject("SELECT status FROM exam_schedule_state WHERE exam_id=?",String.class,exam));
        assertEquals(List.of("PENDING"),reminderStatuses(start,15),"stale CLOSED must not cancel reminders");
    }

    @Test void rescheduleCancelsReplansAndSameStartNeverResendsSentReminder()throws Exception{
        Instant t=BASE.plus(Duration.ofDays(1)),t2=t.plus(Duration.ofHours(1));
        schedule(1,"SCHEDULED",t,List.of(60),"PUBLISHED",owner);
        schedule(2,"RESCHEDULED",t2,List.of(60),"PUBLISHED",owner);
        assertEquals(List.of("CANCELLED"),reminderStatuses(t,60));assertEquals(List.of("PENDING"),reminderStatuses(t2,60));
        schedule(3,"RESCHEDULED",t,List.of(60),"PUBLISHED",owner);
        // The cancelled row does not block replanning the same (startAt, offset).
        assertEquals(List.of("CANCELLED","PENDING"),reminderStatuses(t,60));assertEquals(List.of("CANCELLED"),reminderStatuses(t2,60));
        snapshot("PUBLISHED",3,t,List.of(recipient(student,"ASSIGNED",false),recipient(owner,"OWNER",false)));
        clock.set(t.minus(Duration.ofMinutes(60)).plusSeconds(1));drainJobs();
        assertEquals(List.of("CANCELLED","DONE"),reminderStatuses(t,60));
        assertEquals(1,notificationsFor(student,"EXAM_REMINDER"));assertEquals(1,notificationsFor(owner,"EXAM_REMINDER"));
        schedule(4,"RESCHEDULED",t2,List.of(60),"PUBLISHED",owner);schedule(5,"RESCHEDULED",t,List.of(60),"PUBLISHED",owner);
        assertEquals(List.of("CANCELLED","DONE"),reminderStatuses(t,60),"a sent reminder is never planned again");
        snapshot("PUBLISHED",5,t,List.of(recipient(student,"ASSIGNED",false)));drainJobs();
        assertEquals(1,notificationsFor(student,"EXAM_REMINDER"));
    }

    @Test void noCatchUpCloseCancelsAndReopenPlansOnlyFutureOffsets()throws Exception{
        Instant t=BASE.plus(Duration.ofHours(2));
        schedule(1,"SCHEDULED",t,List.of(1440,15),"PUBLISHED",owner);
        assertEquals(List.of(),reminderStatuses(t,1440),"24h mark already passed: no catch-up");assertEquals(List.of("PENDING"),reminderStatuses(t,15));
        lifecycle(1,"CLOSED",owner);assertEquals(List.of("CANCELLED"),reminderStatuses(t,15));
        clock.set(t.minus(Duration.ofMinutes(30)));lifecycle(2,"OPEN",owner);
        assertEquals(List.of("CANCELLED","PENDING"),reminderStatuses(t,15));
        clock.set(t.minus(Duration.ofMinutes(10)));lifecycle(3,"CLOSED",owner);lifecycle(4,"OPEN",owner);
        assertEquals(List.of("CANCELLED","CANCELLED"),reminderStatuses(t,15),"reopen after the mark plans nothing");
    }

    @Test void staleReminderCancelledLaggingSnapshotWaitsAndLateJobExpires()throws Exception{
        Instant t=BASE.plus(Duration.ofHours(1));
        schedule(1,"SCHEDULED",t,List.of(15),"PUBLISHED",owner);
        db.update("UPDATE scheduling_jobs SET status='DONE' WHERE kind='SCHEDULE'");
        // Authority already at revision 2 (its event not consumed yet): the revision-1 job is stale; the newer event replans.
        snapshot("PUBLISHED",2,t,List.of(recipient(student,"ASSIGNED",false)));
        clock.set(t.minus(Duration.ofMinutes(15)));drainJobs();
        assertEquals(List.of("CANCELLED"),reminderStatuses(t,15));
        assertEquals("superseded by schedule revision 2",db.queryForObject("SELECT last_error FROM scheduling_jobs WHERE kind='REMINDER'",String.class));
        assertEquals(0,notificationsFor(student,"EXAM_REMINDER"));
        clock.set(BASE);schedule(2,"RESCHEDULED",t,List.of(15),"PUBLISHED",owner);
        assertEquals(List.of("CANCELLED","PENDING"),reminderStatuses(t,15),"the newer event replans the same mark");
        db.update("UPDATE scheduling_jobs SET status='DONE' WHERE kind='SCHEDULE'");
        // Authority older than the job (replica lag): wait, never send on unverified data.
        snapshot("PUBLISHED",1,t,List.of(recipient(student,"ASSIGNED",false)));
        clock.set(t.minus(Duration.ofMinutes(15)));drainJobs();
        var job=db.queryForMap("SELECT status,last_error,next_attempt_at FROM scheduling_jobs WHERE kind='REMINDER' AND status<>'CANCELLED'");
        assertEquals("PENDING",job.get("status"));assertEquals("waiting for schedule revision 2",job.get("last_error"));
        assertEquals(clock.instant().plusSeconds(30),((java.sql.Timestamp)job.get("next_attempt_at")).toInstant());
        clock.set(t.plusSeconds(1));drainJobs();
        assertEquals(List.of("CANCELLED","EXPIRED"),reminderStatuses(t,15));assertEquals(0,notificationsFor(student,"EXAM_REMINDER"));
    }

    @Test void publishProducerSequenceGivesStudentsOneScheduleNotice()throws Exception{
        Instant t=BASE.plus(Duration.ofDays(2));snapOffsets=List.of(60);
        // Exam producer: schedule saved in DRAFT, then Publish emits schedule.changed(SCHEDULED, PUBLISHED, same revision) + lifecycle PUBLISHED.
        UUID draft=schedule(1,"SCHEDULED",t,List.of(60),"DRAFT",owner);
        snapshot("PUBLISHED",1,t,everyone());
        UUID pubSchedule=schedule(1,"SCHEDULED",t,List.of(60),"PUBLISHED",owner);UUID pubLifecycle=lifecycle(1,"PUBLISHED",owner);drainJobs();
        assertEquals(Set.of(manager,manager2),recipientsOf(draft));assertEquals(Set.of(),recipientsOf(pubSchedule));
        assertEquals(1,notificationsFor(student,"EXAM_SCHEDULED"));assertEquals(1,notificationsFor(follower,"EXAM_SCHEDULED"));
        assertEquals(0,notificationsFor(done,"EXAM_SCHEDULED"));assertEquals(1,notificationsFor(manager,"EXAM_SCHEDULED"),"managers were told at DRAFT time only");
        assertEquals(Set.of(student,follower),recipientsOf(SchedulingConsumer.studentScheduleKey(exam,1)));assertEquals(Set.of(),recipientsOf(pubLifecycle));
    }

    @Test void legacyExamWithoutEarlierEventsIsSeededAndNotifiedOnceInEitherOrder()throws Exception{
        Instant t=BASE.plus(Duration.ofDays(2));snapOffsets=List.of(60,1440);
        snapshot("PUBLISHED",3,t,everyone());
        // Lifecycle first, then the schedule event of the same revision (reverse broker order).
        lifecycle(1,"PUBLISHED",owner);drainJobs();
        assertEquals(List.of("PENDING"),reminderStatuses(t,60),"reminders seeded from the Exam snapshot");assertEquals(List.of("PENDING"),reminderStatuses(t,1440));
        schedule(3,"SCHEDULED",t,List.of(60,1440),"PUBLISHED",owner);drainJobs();
        assertEquals(1,notificationsFor(student,"EXAM_SCHEDULED"));assertEquals(1,notificationsFor(follower,"EXAM_SCHEDULED"));
        assertEquals(List.of("PENDING"),reminderStatuses(t,60));
    }

    @Test void queuedDeliveriesAreRecheckedAgainstCurrentAuthority()throws Exception{
        Instant t=BASE.plus(Duration.ofDays(1));snapOffsets=List.of(30);
        snapshot("PUBLISHED",1,t,List.of(recipient(student,"ASSIGNED",false),recipient(follower,"FOLLOWER",false)));
        schedule(1,"SCHEDULED",t,List.of(30),"PUBLISHED",owner);drainJobs();UUID scheduled=SchedulingConsumer.studentScheduleKey(exam,1);
        ROUTES.put("/auth/internal/contacts",r->new Response(503,"{}"));while(deliveries.runDue()>0){}
        assertEquals("PENDING",emailStatus(scheduled,student));assertEquals("PENDING",emailStatus(scheduled,follower));
        // follower unfollows; student was also following but stays ASSIGNED.
        snapshot("PUBLISHED",1,t,List.of(recipient(student,"ASSIGNED",false)));
        ROUTES.put("/auth/internal/contacts",r->new Response(200,"[{\"userId\":\""+student+"\",\"email\":\"s@example.test\",\"displayName\":\"S\"}]"));
        clock.advance(Duration.ofMinutes(1));while(deliveries.runDue()>0){}
        assertEquals("SENT",emailStatus(scheduled,student));
        assertEquals("SKIPPED:RECIPIENT_REMOVED",db.queryForObject("SELECT d.status||':'||d.skip_reason FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.source_event_id=? AND n.recipient_user_id=? AND d.channel='EMAIL'",String.class,
            SchedulingConsumer.studentScheduleKey(exam,1),follower));
        // Reminder fanned out, email delivery fails, then the Exam is rescheduled: the queued reminder is skipped.
        clock.set(t.minus(Duration.ofMinutes(30)));drainJobs();
        UUID reminder=SchedulingConsumer.reminderKey(exam,t,30);
        ROUTES.put("/auth/internal/contacts",r->new Response(503,"{}"));while(deliveries.runDue()>0){}
        assertEquals("PENDING",emailStatus(reminder,student));
        snapshot("PUBLISHED",2,t.plus(Duration.ofHours(1)),List.of(recipient(student,"ASSIGNED",false)));
        clock.advance(Duration.ofMinutes(1));while(deliveries.runDue()>0){}
        assertEquals("SKIPPED",emailStatus(reminder,student));
        assertEquals("SCHEDULE_CHANGED",db.queryForObject("SELECT d.skip_reason FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.source_event_id=? AND d.channel='EMAIL'",String.class,reminder));
    }

    @Test void lateReminderBeforeStartDoesNotClaimTheConfiguredLeadTime()throws Exception{
        Instant t=BASE.plus(Duration.ofDays(2));snapOffsets=List.of(1440);
        schedule(1,"SCHEDULED",t,List.of(1440),"PUBLISHED",owner);
        db.update("UPDATE scheduling_jobs SET status='DONE' WHERE kind='SCHEDULE'");
        snapshot("PUBLISHED",1,t,List.of(recipient(student,"ASSIGNED",false)));
        // The 24h job only runs 30 seconds before start (worker downtime): still valid, but must not say "24 giờ".
        clock.set(t.minusSeconds(30));drainJobs();
        assertEquals(List.of("DONE"),reminderStatuses(t,1440));
        var n=db.queryForMap("SELECT message,metadata->>'offsetMinutes' AS offset FROM notifications WHERE source_event_id=? AND recipient_user_id=?",
            SchedulingConsumer.reminderKey(exam,t,1440),student);
        String message=n.get("message").toString();
        assertFalse(message.contains("24 giờ") || message.contains("1 ngày") || message.contains("Còn"),message);
        assertTrue(message.contains("Bắt đầu: 07:00 08/10/2026 (GMT+07:00, Asia/Ho_Chi_Minh)"),message);
        assertEquals("1440",n.get("offset"));
        assertFalse(message.contains("joinCode") || message.contains("correct"));
        // Still no catch-up and still expires at start.
        clock.set(t.plusSeconds(1));schedule(2,"RESCHEDULED",t.plus(Duration.ofMinutes(30)),List.of(1440,15),"PUBLISHED",owner);
        assertEquals(List.of(),reminderStatuses(t.plus(Duration.ofMinutes(30)),1440));
        assertEquals(List.of("PENDING"),reminderStatuses(t.plus(Duration.ofMinutes(30)),15));
        clock.set(t.plus(Duration.ofMinutes(31)));drainJobs();
        assertEquals(List.of("EXPIRED"),reminderStatuses(t.plus(Duration.ofMinutes(30)),15));
    }

    @Test void openReachesAllRelatedCloseOnlyUnsubmittedAndNeverTheActor()throws Exception{
        Instant t=BASE.plus(Duration.ofHours(1));
        schedule(1,"SCHEDULED",t,List.of(),"PUBLISHED",owner);snapshot("OPEN",1,t,everyone());
        UUID opened=lifecycle(1,"OPEN",null);drainJobs();
        assertEquals(Set.of(owner,manager,manager2,student,follower,done),recipientsOf(opened),"AUTO open: all managers and every related student");
        assertTrue(db.queryForObject("SELECT message FROM notifications WHERE source_event_id=? AND recipient_user_id=?",String.class,opened,student).contains("vào thi từ"));
        UUID closed=lifecycle(2,"CLOSED",manager);drainJobs();
        assertEquals(Set.of(owner,manager2,student,follower),recipientsOf(closed),"manual close: not the actor, not submitted students");
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.source_event_id IN (?,?) AND d.channel='EMAIL'",Long.class,opened,closed),
            "Open/Close are in-app and push only");
    }

    @Test void recipientChangesAreReadLiveWithoutExtraNotifications()throws Exception{
        Instant t=BASE.plus(Duration.ofDays(1));
        schedule(1,"SCHEDULED",t,List.of(30),"PUBLISHED",owner);snapshot("PUBLISHED",1,t,everyone());drainJobs();
        long before=db.queryForObject("SELECT count(*) FROM notifications WHERE entity_id=?",Long.class,exam);
        var causes=List.of("FOLLOWED","ASSIGNED","JOINED","UNFOLLOWED");
        for(var cause:causes)deliver("exam.recipients.changed",Map.of("examId",exam.toString(),"recipientRevision",causes.indexOf(cause)+1,"cause",cause,"actorUserId",owner.toString()));
        var stale=new HashMap<String,Object>(Map.of("examId",exam.toString(),"recipientRevision",2,"cause","UNASSIGNED"));stale.put("actorUserId",null);deliver("exam.recipients.changed",stale);
        assertEquals(4L,db.queryForObject("SELECT recipient_revision FROM exam_schedule_state WHERE exam_id=?",Long.class,exam));
        drainJobs();
        assertEquals(before,db.queryForObject("SELECT count(*) FROM notifications WHERE entity_id=?",Long.class,exam),"assignment/follow changes send nothing");
        assertEquals(List.of("PENDING"),reminderStatuses(t,30));
        // Follower unfollowed and left; student still assigned (also a former follower) keeps the reminder.
        snapshot("PUBLISHED",1,t,List.of(recipient(owner,"OWNER",false),recipient(student,"ASSIGNED",false)));
        clock.set(t.minus(Duration.ofMinutes(30)));drainJobs();
        assertEquals(1,notificationsFor(student,"EXAM_REMINDER"));assertEquals(0,notificationsFor(follower,"EXAM_REMINDER"));
    }

    @Test void providerFailuresFailClosedWithBackoff()throws Exception{
        schedule(1,"SCHEDULED",BASE.plus(Duration.ofDays(1)),List.of(),"PUBLISHED",owner);
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(401,"{}"));drainJobs();
        var job=db.queryForMap("SELECT status,attempts,last_error,next_attempt_at FROM scheduling_jobs WHERE kind='SCHEDULE'");
        assertEquals("PENDING",job.get("status"));assertEquals(1,job.get("attempts"));assertTrue(job.get("last_error").toString().contains("credential"));
        assertEquals(BASE.plusSeconds(30),((java.sql.Timestamp)job.get("next_attempt_at")).toInstant());
        var call=REQUESTS.getFirst();assertEquals("test-exam-notification-token",call.header("x-service-token"));assertNull(call.header("authorization"));
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(503,"{}"));clock.advance(Duration.ofSeconds(30));drainJobs();
        assertEquals(clock.instant().plusSeconds(60),((java.sql.Timestamp)db.queryForObject("SELECT next_attempt_at FROM scheduling_jobs WHERE kind='SCHEDULE'",java.sql.Timestamp.class)).toInstant());
        ROUTES.put("/exams/"+exam+"/internal/notification-recipients",r->new Response(200,"{\"recipients\":\"not-a-list\"}"));clock.advance(Duration.ofMinutes(1));drainJobs();
        assertEquals("Exam recipients response invalid",db.queryForObject("SELECT last_error FROM scheduling_jobs WHERE kind='SCHEDULE'",String.class));
        ROUTES.remove("/exams/"+exam+"/internal/notification-recipients");clock.advance(Duration.ofMinutes(2));drainJobs();
        assertEquals("CANCELLED",db.queryForObject("SELECT status FROM scheduling_jobs WHERE kind='SCHEDULE'",String.class));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM notifications WHERE entity_id=?",Long.class,exam));
        int before=REQUESTS.size();
        var blank=new ExamRecipientClient(json,stubUrl(),"");
        assertEquals(ProviderException.Kind.CONFIG,assertThrows(ProviderException.class,()->blank.recipients(exam)).kind());
        assertEquals(ProviderException.Kind.CONFIG,assertThrows(ProviderException.class,()->new AuthContactClient(json,stubUrl()," ").contacts(List.of(owner))).kind());
        assertEquals(before,REQUESTS.size(),"missing credentials never reach the network");
    }

    @Test void concurrentWorkersProcessEachJobOnce()throws Exception{
        snapshot("PUBLISHED",30,null,List.of(recipient(owner,"OWNER",false),recipient(student,"ASSIGNED",false),recipient(follower,"FOLLOWER",false)));
        for(int r=1;r<=30;r++)schedule(r,"RESCHEDULED",BASE.plus(Duration.ofDays(2)).plusSeconds(r),List.of(),"PUBLISHED",manager);
        assertEquals(30,pending("SCHEDULE"));
        var pool=Executors.newFixedThreadPool(4);
        try{var all=new ArrayList<Future<?>>();for(int i=0;i<4;i++)all.add(pool.submit(this::drainJobs));for(var f:all)f.get(60,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(30L,db.queryForObject("SELECT count(*) FROM scheduling_jobs WHERE kind='SCHEDULE' AND status='DONE'",Long.class));
        assertEquals(30,REQUESTS.stream().filter(r->r.path().endsWith("/notification-recipients")).count(),"each job fetched recipients exactly once");
        assertEquals(90L,db.queryForObject("SELECT count(*) FROM notifications WHERE entity_id=?",Long.class,exam));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM (SELECT source_event_id,recipient_user_id FROM notifications WHERE entity_id=? GROUP BY 1,2 HAVING count(*)>1) d",Long.class,exam));
    }

    @Test void emailGoesThroughRealSmtpWithAuthContactsAndIsNotResent()throws Exception{
        String to="hs-"+student.toString().substring(0,8)+"@example.test";
        Instant t=BASE.plus(Duration.ofDays(1));
        snapshot("PUBLISHED",1,t,List.of(recipient(student,"ASSIGNED",false),recipient(follower,"FOLLOWER",false)));
        UUID scheduled=SchedulingConsumer.studentScheduleKey(exam,1);schedule(1,"SCHEDULED",t,List.of(),"PUBLISHED",owner);drainJobs();
        ROUTES.put("/auth/internal/contacts",r->new Response(401,"{}"));
        while(deliveries.runDue()>0){}
        assertEquals(List.of("PENDING"),db.queryForList("SELECT DISTINCT status FROM notification_deliveries WHERE channel='EMAIL'",String.class),"Auth credential rejected: retry, never SENT");
        ROUTES.put("/auth/internal/contacts",r->new Response(200,"[{\"userId\":\""+student+"\",\"email\":\""+to+"\",\"displayName\":\"Học sinh\"}]"));
        clock.advance(Duration.ofMinutes(1));while(deliveries.runDue()>0){}
        var call=REQUESTS.stream().filter(r->r.path().equals("/auth/internal/contacts")).reduce((a,b)->b).orElseThrow();
        assertEquals("test-contact-token",call.header("x-service-token"));assertNull(call.header("authorization"));
        assertEquals(Set.of(student.toString(),follower.toString()),new HashSet<>(toList(json.readTree(call.body()).get("userIds"))));
        assertEquals("SENT",emailStatus(scheduled,student));assertEquals("SKIPPED",emailStatus(scheduled,follower));
        JsonNode found=mailpit("/api/v1/search?query="+URLEncoder.encode("to:\""+to+"\"",StandardCharsets.UTF_8));
        assertEquals(1,found.get("messages_count").asInt());
        JsonNode msg=mailpit("/api/v1/message/"+found.get("messages").get(0).get("ID").asText());
        assertEquals("[EXAMINI] Đã có lịch thi",msg.get("Subject").asText());
        String text=msg.get("Text").asText();
        assertTrue(text.contains("Kỳ thi \"Toán 12\" đã có lịch") && text.contains("GMT+07:00, Asia/Ho_Chi_Minh") && text.contains("http://app.test/exam/lobby/"+exam),text);
        assertFalse(text.toLowerCase().contains("joincode") || text.contains("correct"));
        // the exam travels as an iCalendar attachment with the same UID as the calendar feed
        JsonNode attachments=msg.get("Attachments");
        assertEquals(1,attachments.size());
        assertEquals("lich-thi.ics",attachments.get(0).get("FileName").asText());
        assertTrue(attachments.get(0).get("ContentType").asText().startsWith("text/calendar"));
        assertTrue(text.contains("lich-thi.ics"));
        clock.advance(Duration.ofHours(1));while(deliveries.runDue()>0){}
        assertEquals(1,mailpit("/api/v1/search?query="+URLEncoder.encode("to:\""+to+"\"",StandardCharsets.UTF_8)).get("messages_count").asInt(),"SENT email is not resent");
    }
    String emailStatus(UUID source,UUID user){
        return db.queryForObject("SELECT d.status FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.source_event_id=? AND n.recipient_user_id=? AND d.channel='EMAIL'",String.class,source,user);
    }
    static List<String> toList(JsonNode a){var l=new ArrayList<String>();a.forEach(v->l.add(v.asText()));return l;}
    JsonNode mailpit(String path)throws Exception{
        String host=Optional.ofNullable(System.getenv("TEST_SMTP_HOST")).orElse("localhost");
        var res=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+host+":8025"+path)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,res.statusCode());return json.readTree(res.body());
    }
}
