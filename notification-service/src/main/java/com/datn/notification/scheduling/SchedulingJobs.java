package com.datn.notification.scheduling;
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
    static final Set<String> EMAIL_TYPES=Set.of("EXAM_SCHEDULED","EXAM_RESCHEDULED","EXAM_UNSCHEDULED","EXAM_REMINDER");
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;private final Clock clock;
    private final ExamRecipientClient exams;private final int batch;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SchedulingJobs.class);
    public SchedulingJobs(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Clock clock,ExamRecipientClient exams,@Value("${notification.jobs.batch-size:20}") int batch){
        this.db=db;this.tx=tx;this.json=json;this.clock=clock;this.exams=exams;this.batch=batch;
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
        var facts=new SchedulingMessages.Schedule(snap.examTitle(),snap.startAt(),snap.endAt(),snap.timezone(),snap.deadlineRule());
        switch(job.kind()){
            case "REMINDER" -> {
                if(!now.isBefore(job.startAt())){finish(job,"EXPIRED","startAt passed before delivery",null);return;}
                if(snap.status().equals("CLOSED")){finish(job,"CANCELLED","exam closed",null);return;}
                if(snap.scheduleRevision()!=job.revision()){retry(job,"waiting for schedule revision "+snap.scheduleRevision(),now.plus(WAIT_FOR_REVISION));return;}
                if(!job.startAt().equals(snap.startAt())){finish(job,"CANCELLED","startAt changed",null);return;}
                targets=targets(snap,null,!snap.status().equals("DRAFT"),true);
                content=SchedulingMessages.reminder(facts,job.offset());expires=job.startAt();
            }
            case "SCHEDULE" -> {
                Schedule e=read(job.payload(),Schedule.class);
                // Students learn about a DRAFT schedule only when the Exam is published (LIFECYCLE PUBLISHED).
                targets=targets(snap,e.actorUserId(),!e.examStatus().equals("DRAFT"),true);
                content=SchedulingMessages.scheduleChanged(e.changeType(),new SchedulingMessages.Schedule(e.examTitle(),e.startAt(),e.endAt(),e.timezone(),e.deadlineRule()));
            }
            case "LIFECYCLE" -> {
                Lifecycle e=read(job.payload(),Lifecycle.class);
                switch(e.status()){
                    case "PUBLISHED" -> {
                        if(snap.startAt()==null && snap.endAt()==null){finish(job,"DONE",null,null);return;}
                        targets=targets(snap,e.actorUserId(),true,true).stream().filter(t->!t.manager()).toList();
                        content=SchedulingMessages.scheduleChanged("SCHEDULED",facts);
                    }
                    case "OPEN" -> {targets=targets(snap,e.actorUserId(),true,false);content=SchedulingMessages.opened(facts,now);}
                    default -> {targets=targets(snap,e.actorUserId(),true,true);content=SchedulingMessages.closed(facts);}
                }
            }
            default -> throw new IllegalStateException("Unknown job kind");
        }
        fanOut(job,snap.examId(),targets,content,expires);
    }

    /** Managers (OWNER/MANAGER) except the manual actor; students (ASSIGNED/JOINED/FOLLOWER) when allowed, optionally only those who have not submitted. */
    static List<Target> targets(Snapshot snap,UUID actor,boolean students,boolean unsubmittedOnly){
        return snap.recipients().stream().filter(r->!r.userId().equals(actor))
            .filter(r->r.manager() || (students && !(unsubmittedOnly && r.submitted())))
            .map(r->new Target(r.userId(),r.manager())).toList();
    }

    private void fanOut(Job job,UUID examId,List<Target> targets,SchedulingMessages.Content c,Instant expires){
        Instant now=clock.instant();
        tx.executeWithoutResult(status->{
            int done=db.update("UPDATE scheduling_jobs SET status='DONE',locked_until=NULL,last_error=NULL,updated_at=? WHERE id=? AND status='RUNNING'",ts(now),job.id());
            if(done==0)throw new IllegalStateException("job cancelled or reclaimed during fan-out");
            for(Target t:targets){
                var meta=new HashMap<>(c.metadata());meta.put("examId",examId.toString());
                meta.put("link",t.manager()?"/exams/"+examId:"/exam/lobby/"+examId);
                List<UUID> inserted=db.queryForList("""
                    INSERT INTO notifications(id,recipient_user_id,type,title,message,entity_type,entity_id,metadata,source_event_id,source_event_type,created_at)
                    VALUES(?,?,?,?,?,'EXAM',?,CAST(? AS jsonb),?,?,?) ON CONFLICT(source_event_id,recipient_user_id) DO NOTHING RETURNING id""",UUID.class,
                    UUID.randomUUID(),t.userId(),c.type(),c.title(),c.message(),examId,write(meta),job.sourceId(),"scheduling."+job.kind().toLowerCase(),ts(now));
                if(inserted.isEmpty())continue;
                UUID id=inserted.getFirst();
                if(EMAIL_TYPES.contains(c.type()))delivery(id,t.userId(),"EMAIL",null,"PENDING",null,now,expires);
                List<UUID> subs=db.queryForList("SELECT id FROM push_subscriptions WHERE user_id=? AND revoked_at IS NULL",UUID.class,t.userId());
                if(subs.isEmpty())delivery(id,t.userId(),"PUSH",null,"SKIPPED","NO_SUBSCRIPTION",now,expires);
                for(UUID sub:subs)delivery(id,t.userId(),"PUSH",sub,"PENDING",null,now,expires);
            }
        });
    }
    private void delivery(UUID notification,UUID user,String channel,UUID subscription,String status,String reason,Instant now,Instant expires){
        db.update("""
            INSERT INTO notification_deliveries(id,notification_id,recipient_user_id,channel,subscription_id,status,skip_reason,next_attempt_at,expires_at,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",UUID.randomUUID(),notification,user,channel,subscription,status,reason,ts(now),ts(expires),ts(now),ts(now));
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
