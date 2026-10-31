package com.datn.notification.scheduling;
import com.datn.notification.delivery.DeliveryQueue;
import com.datn.notification.client.*;
import com.datn.notification.client.ExamRecipientClient.*;
import com.datn.notification.service.SchedulingEvents.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static com.datn.notification.scheduling.SchedulingConsumer.*;

/**
 * Executes due Scheduling jobs. Jobs are claimed with FOR UPDATE SKIP LOCKED and a lease, so several instances (or a
 * crashed one) never double-process; recipients come from the Exam snapshot at execution time, never a frozen list.
 * Fan-out (inbox rows + channel deliveries + job DONE) commits atomically; a job cancelled meanwhile rolls back.
 */
@Service
public class SchedulingJobs {
    static final Duration LEASE=Duration.ofMinutes(2),WAIT_FOR_REVISION=Duration.ofSeconds(30),MAX_BACKOFF=Duration.ofMinutes(30);
    static final Set<String> EMAIL_TYPES=Set.of("EXAM_SCHEDULED","EXAM_RESCHEDULED","EXAM_UNSCHEDULED","EXAM_REMINDER","GRADING_REVIEW_REQUIRED");
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;private final Clock clock;
    private final ExamRecipientClient exams;private final SchedulingConsumer consumer;private final DeliveryQueue deliveries;private final int batch;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SchedulingJobs.class);
    public SchedulingJobs(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Clock clock,ExamRecipientClient exams,SchedulingConsumer consumer,DeliveryQueue deliveries,
                          @Value("${notification.jobs.batch-size:20}") int batch){
        this.db=db;this.tx=tx;this.json=json;this.clock=clock;this.exams=exams;this.consumer=consumer;this.deliveries=deliveries;this.batch=batch;
    }

    record Job(UUID id,String kind,UUID examId,UUID sourceId,long revision,Instant startAt,Integer offset,String payload,Instant expiresAt,int attempts){}
    record Target(UUID userId,boolean manager){}

    /** Runs one batch; returns the number of jobs claimed. */
    public int runDue(){
        Instant now=clock.instant();
        db.update("""
            UPDATE scheduling_jobs SET status=CASE WHEN kind='REMINDER' THEN 'EXPIRED' ELSE 'FAILED' END,last_error=COALESCE(last_error,'expired before processing'),locked_until=NULL,updated_at=?
            WHERE (status='PENDING' OR (status='RUNNING' AND locked_until<?)) AND expires_at<=?""",ts(now),ts(now),ts(now));
        List<Job> jobs=db.query("""
            UPDATE scheduling_jobs SET status='RUNNING',locked_until=?,attempts=attempts+1,updated_at=?
            WHERE id IN (SELECT id FROM scheduling_jobs WHERE ((status='PENDING' AND next_attempt_at<=?) OR (status='RUNNING' AND locked_until<?)) AND expires_at>?
                         ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
            RETURNING id,kind,exam_id,source_id,revision,start_at,offset_minutes,payload::text,expires_at,attempts""",
            (r,i)->new Job(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getLong(5),instant(r.getTimestamp(6)),
                (Integer)r.getObject(7),r.getString(8),instant(r.getTimestamp(9)),r.getInt(10)),
            ts(now.plus(LEASE)),ts(now),ts(now),ts(now),ts(now),batch);
        for(Job job:jobs){
            try{execute(job);}
            catch(ProviderException e){
                if(e.kind()==ProviderException.Kind.NOT_FOUND)finish(job,"CANCELLED","exam no longer exists",null);
                else retry(job,e.getMessage(),null);
            }catch(RuntimeException e){log.warn("Scheduling job {} failed: {}",job.id(),e.toString());retry(job,"internal error: "+e.getClass().getSimpleName(),null);}
        }
        return jobs.size();
    }

    private void execute(Job job){
        Instant now=clock.instant();
        Snapshot snap=exams.recipients(job.examId());
        List<Target> targets;SchedulingMessages.Content content;Instant expires=now.plus(EVENT_JOB_TTL);
        long revision=snap.scheduleRevision();UUID studentSource=job.sourceId();
        var facts=new SchedulingMessages.Schedule(snap.examTitle(),snap.startAt(),snap.endAt(),snap.timezone(),snap.deadlineRule());
        switch(job.kind()){
            case "REMINDER" -> {
                if(!now.isBefore(job.startAt())){finish(job,"EXPIRED","startAt passed before delivery",null);return;}
                if(snap.status().equals("CLOSED")){finish(job,"CANCELLED","exam closed",null);return;}
                // Newer authority: this job is stale (the newer event replans). Older snapshot: replication lag, wait.
                if(snap.scheduleRevision()>job.revision()){finish(job,"CANCELLED","superseded by schedule revision "+snap.scheduleRevision(),null);return;}
                if(snap.scheduleRevision()<job.revision()){retry(job,"waiting for schedule revision "+job.revision(),now.plus(WAIT_FOR_REVISION));return;}
                if(!job.startAt().equals(snap.startAt())){finish(job,"CANCELLED","startAt changed",null);return;}
                targets=targets(snap,null,!snap.status().equals("DRAFT"),true);
                content=SchedulingMessages.reminder(facts,job.offset());expires=job.startAt();studentSource=job.sourceId();
            }
            case "SCHEDULE" -> {
                Schedule e=read(job.payload(),Schedule.class);
                // Students learn about a DRAFT schedule only when the Exam is published (LIFECYCLE PUBLISHED).
                targets=targets(snap,e.actorUserId(),!e.examStatus().equals("DRAFT"),true);
                content=SchedulingMessages.scheduleChanged(e.changeType(),new SchedulingMessages.Schedule(e.examTitle(),e.startAt(),e.endAt(),e.timezone(),e.deadlineRule()));
                revision=e.scheduleRevision();studentSource=SchedulingConsumer.studentScheduleKey(job.examId(),revision);
            }
            case "LIFECYCLE" -> {
                Lifecycle e=read(job.payload(),Lifecycle.class);
                consumer.seedFromSnapshot(snap);
                switch(e.status()){
                    case "PUBLISHED" -> {
                        if(snap.startAt()==null && snap.endAt()==null){finish(job,"DONE",null,null);return;}
                        targets=targets(snap,e.actorUserId(),true,true).stream().filter(t->!t.manager()).toList();
                        content=SchedulingMessages.scheduleChanged("SCHEDULED",facts);studentSource=SchedulingConsumer.studentScheduleKey(job.examId(),revision);
                    }
                    case "OPEN" -> {targets=targets(snap,e.actorUserId(),true,false);content=SchedulingMessages.opened(facts,now);}
                    default -> {targets=targets(snap,e.actorUserId(),true,true);content=SchedulingMessages.closed(facts);}
                }
            }
            case "GRADING_REVIEW" -> {
                // OWNER/MANAGER only, from the current Exam snapshot: PROCTOR and students are never in a manager role, and a
                // teacher whose membership was revoked is no longer in the snapshot.
                var e=read(job.payload(),com.datn.notification.service.GradingEvents.ReviewRequired.class);
                targets=snap.recipients().stream().filter(Recipient::manager).map(r->new Target(r.userId(),true)).toList();
                content=com.datn.notification.grading.GradingMessages.reviewRequired(e.examTitle(),e.examId(),e.sessionId(),e.resultId(),e.resultRevision());
                revision=e.resultRevision();
            }
            default -> throw new IllegalStateException("Unknown job kind");
        }
        fanOut(job,snap.examId(),targets,content,expires,revision,studentSource);
    }

    /** Managers (OWNER/MANAGER) except the manual actor; students (ASSIGNED/JOINED/FOLLOWER) when allowed, optionally only those who have not submitted. */
    static List<Target> targets(Snapshot snap,UUID actor,boolean students,boolean unsubmittedOnly){
        return snap.recipients().stream().filter(r->!r.userId().equals(actor))
            .filter(r->r.manager() || (students && !(unsubmittedOnly && r.submitted())))
            .map(r->new Target(r.userId(),r.manager())).toList();
    }

    private void fanOut(Job job,UUID examId,List<Target> targets,SchedulingMessages.Content c,Instant expires,long revision,UUID studentSource){
        Instant now=clock.instant();
        tx.executeWithoutResult(status->{
            int done=db.update("UPDATE scheduling_jobs SET status='DONE',locked_until=NULL,last_error=NULL,updated_at=? WHERE id=? AND status='RUNNING'",ts(now),job.id());
            if(done==0)throw new IllegalStateException("job cancelled or reclaimed during fan-out");
            for(Target t:targets){
                var meta=new HashMap<>(c.metadata());meta.put("examId",examId.toString());
                meta.putIfAbsent("link",t.manager()?"/exams/"+examId:"/exam/lobby/"+examId);
                // Used to re-check authority before each channel send/retry (DeliveryWorker).
                meta.put("audience",t.manager()?"MANAGER":"STUDENT");meta.put("scheduleRevision",String.valueOf(revision));
                List<UUID> inserted=db.queryForList("""
                    INSERT INTO notifications(id,recipient_user_id,type,title,message,entity_type,entity_id,metadata,source_event_id,source_event_type,created_at)
                    VALUES(?,?,?,?,?,'EXAM',?,CAST(? AS jsonb),?,?,?) ON CONFLICT(source_event_id,recipient_user_id) DO NOTHING RETURNING id""",UUID.class,
                    UUID.randomUUID(),t.userId(),c.type(),c.title(),c.message(),examId,write(meta),t.manager()?job.sourceId():studentSource,"scheduling."+job.kind().toLowerCase(),ts(now));
                if(inserted.isEmpty())continue;
                UUID id=inserted.getFirst();
                deliveries.enqueue(id,t.userId(),c.type(),EMAIL_TYPES.contains(c.type()),now,expires);
            }
        });
    }

    private void finish(Job job,String status,String error,Instant next){
        db.update("UPDATE scheduling_jobs SET status=?,last_error=?,locked_until=NULL,next_attempt_at=COALESCE(?,next_attempt_at),updated_at=? WHERE id=? AND status='RUNNING'",
            status,error,ts(next),ts(clock.instant()),job.id());
    }
    /** Exponential backoff (30s doubling, capped at 30min) unless an explicit retry time is given; expiry ends retries. */
    private void retry(Job job,String error,Instant at){
        Instant next=at!=null?at:clock.instant().plus(backoff(job.attempts()));
        finish(job,"PENDING",error==null?null:error.substring(0,Math.min(300,error.length())),next);
    }
    static Duration backoff(int attempts){
        Duration d=Duration.ofSeconds(30).multipliedBy(1L<<Math.min(Math.max(attempts-1,0),10));
        return d.compareTo(MAX_BACKOFF)>0?MAX_BACKOFF:d;
    }
    private <T> T read(String s,Class<T> type){try{return json.readValue(s,type);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
}
