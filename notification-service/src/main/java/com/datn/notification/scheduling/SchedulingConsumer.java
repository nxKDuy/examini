package com.datn.notification.scheduling;
import com.datn.notification.service.SchedulingEvents.*;
import com.datn.notification.service.SchedulingEvents.Recipients;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Records Scheduling events in the listener transaction (committed before the broker ack): orders them by revision,
 * queues one fan-out job per accepted event and replans reminder jobs. Recipients are resolved later from Exam.
 */
@Service @RequiredArgsConstructor @Transactional
public class SchedulingConsumer {
    static final Duration EVENT_JOB_TTL=Duration.ofHours(24);
    private final JdbcTemplate db;private final ObjectMapper json;private final Clock clock;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SchedulingConsumer.class);

    record State(String status,long scheduleRevision,long lifecycleRevision,long recipientRevision,Instant startAt,List<Integer> offsets){}

    public void onSchedule(UUID eventId,Schedule e){
        State s=lock(e.examId(),e.examTitle());
        if(e.scheduleRevision()<=s.scheduleRevision()){log.info("Skipped stale schedule revision {} for exam {}",e.scheduleRevision(),e.examId());return;}
        // A schedule event's examStatus only seeds the status until lifecycle events take over.
        String status=s.lifecycleRevision()==0?e.examStatus():s.status();
        db.update("""
            UPDATE exam_schedule_state SET exam_title=?,status=?,schedule_revision=?,start_at=?,end_at=?,timezone=?,deadline_rule=?,reminder_offsets=CAST(? AS jsonb),updated_at=?
            WHERE exam_id=?""",e.examTitle(),status,e.scheduleRevision(),ts(e.startAt()),ts(e.endAt()),e.timezone(),e.deadlineRule(),write(e.reminderOffsetsMinutes()),ts(now()),e.examId());
        queueEvent("SCHEDULE",eventId,e.examId(),e.scheduleRevision(),e);
        replan(e.examId(),e.scheduleRevision(),status,e.startAt(),e.reminderOffsetsMinutes());
    }

    public void onLifecycle(UUID eventId,Lifecycle e){
        State s=lock(e.examId(),e.examTitle());
        if(e.lifecycleRevision()<=s.lifecycleRevision()){log.info("Skipped stale lifecycle revision {} for exam {}",e.lifecycleRevision(),e.examId());return;}
        db.update("UPDATE exam_schedule_state SET exam_title=?,status=?,lifecycle_revision=?,updated_at=? WHERE exam_id=?",e.examTitle(),e.status(),e.lifecycleRevision(),ts(now()),e.examId());
        queueEvent("LIFECYCLE",eventId,e.examId(),e.lifecycleRevision(),e);
        // Close cancels reminders; Reopen/Publish replans only offsets that are still in the future.
        replan(e.examId(),s.scheduleRevision(),e.status(),s.startAt(),s.offsets());
    }

    /**
     * Recipients are never frozen: reminder fan-out reads the current Exam snapshot, so assignment/unassignment/follow
     * changes need no per-user jobs and produce no notification (no catch-up). This only orders the revision and makes
     * sure future reminders exist for the current schedule.
     */
    public void onRecipients(Recipients e){
        db.update("INSERT INTO exam_schedule_state(exam_id,exam_title) VALUES(?,'') ON CONFLICT(exam_id) DO NOTHING",e.examId());
        State s=lock(e.examId());
        if(e.recipientRevision()<=s.recipientRevision()){log.info("Skipped stale recipient revision {} for exam {}",e.recipientRevision(),e.examId());return;}
        db.update("UPDATE exam_schedule_state SET recipient_revision=?,updated_at=? WHERE exam_id=?",e.recipientRevision(),ts(now()),e.examId());
        replan(e.examId(),s.scheduleRevision(),s.status(),s.startAt(),s.offsets());
    }

    /**
     * Seeds the schedule from the authoritative Exam snapshot when no newer schedule event was consumed (e.g. an Exam
     * scheduled before the producer existed, published later). Never moves any revision backwards.
     */
    public void seedFromSnapshot(com.datn.notification.client.ExamRecipientClient.Snapshot s){
        State st=lock(s.examId(),s.examTitle());
        if(s.scheduleRevision()<=st.scheduleRevision())return;
        String status=s.lifecycleRevision()>=st.lifecycleRevision()?s.status():st.status();
        db.update("""
            UPDATE exam_schedule_state SET exam_title=?,status=?,schedule_revision=?,start_at=?,end_at=?,timezone=?,deadline_rule=?,reminder_offsets=CAST(? AS jsonb),updated_at=?
            WHERE exam_id=?""",s.examTitle(),status,s.scheduleRevision(),ts(s.startAt()),ts(s.endAt()),s.timezone(),s.deadlineRule(),write(s.reminderOffsetsMinutes()),ts(now()),s.examId());
        replan(s.examId(),s.scheduleRevision(),status,s.startAt(),s.reminderOffsetsMinutes());
    }
    /**
     * Students get one logical "schedule" notice per schedule revision, whether it comes from a schedule event of a
     * published Exam or from the Publish lifecycle event (producers may emit both for the same revision).
     */
    public static UUID studentScheduleKey(UUID examId,long scheduleRevision){
        return UUID.nameUUIDFromBytes(("student-schedule:"+examId+":"+scheduleRevision).getBytes(StandardCharsets.UTF_8));
    }

    /** Serializes all processing of one Exam (row lock) so concurrent consumers cannot interleave revisions. */
    private State lock(UUID examId,String title){
        db.update("INSERT INTO exam_schedule_state(exam_id,exam_title) VALUES(?,?) ON CONFLICT(exam_id) DO NOTHING",examId,title);
        return lock(examId);
    }
    private State lock(UUID examId){
        return db.queryForObject("SELECT status,schedule_revision,lifecycle_revision,recipient_revision,start_at,reminder_offsets FROM exam_schedule_state WHERE exam_id=? FOR UPDATE",
            (r,i)->new State(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),instant(r.getTimestamp(5)),read(r.getString(6))),examId);
    }
    private void queueEvent(String kind,UUID eventId,UUID examId,long revision,Object payload){
        Instant now=now();
        db.update("""
            INSERT INTO scheduling_jobs(id,kind,exam_id,source_id,revision,payload,status,due_at,next_attempt_at,expires_at)
            VALUES(?,?,?,?,?,CAST(? AS jsonb),'PENDING',?,?,?) ON CONFLICT(kind,source_id) WHERE status <> 'CANCELLED' DO NOTHING""",
            UUID.randomUUID(),kind,examId,eventId,revision,write(payload),ts(now),ts(now),ts(now.plus(EVENT_JOB_TTL)));
    }

    /**
     * Desired reminders = future (startAt - offset) for an unclosed Exam with a startAt. Jobs outside that set are cancelled,
     * kept jobs move to the new revision, and missing ones are inserted. Offsets already in the past are never caught up,
     * and a DONE/EXPIRED/FAILED reminder for the same (startAt, offset) blocks re-insertion so it is never sent twice.
     */
    void replan(UUID examId,long revision,String status,Instant startAt,List<Integer> offsets){
        Instant now=now();
        var desired=new LinkedHashMap<UUID,Integer>();
        if(startAt!=null && !"CLOSED".equals(status))for(int offset:offsets)
            if(startAt.minus(Duration.ofMinutes(offset)).isAfter(now))desired.put(reminderKey(examId,startAt,offset),offset);
        db.update("UPDATE scheduling_jobs SET status='CANCELLED',locked_until=NULL,updated_at=? WHERE exam_id=? AND kind='REMINDER' AND status IN ('PENDING','RUNNING') AND NOT (source_id = ANY(?))",
            ts(now),examId,desired.keySet().toArray(UUID[]::new));
        db.update("UPDATE scheduling_jobs SET revision=?,updated_at=? WHERE exam_id=? AND kind='REMINDER' AND status IN ('PENDING','RUNNING')",revision,ts(now),examId);
        desired.forEach((key,offset)->{
            Instant fire=startAt.minus(Duration.ofMinutes(offset));
            db.update("""
                INSERT INTO scheduling_jobs(id,kind,exam_id,source_id,revision,start_at,offset_minutes,status,due_at,next_attempt_at,expires_at)
                VALUES(?,'REMINDER',?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT(kind,source_id) WHERE status <> 'CANCELLED' DO NOTHING""",
                UUID.randomUUID(),examId,key,revision,ts(startAt),offset,ts(fire),ts(fire),ts(startAt));
        });
    }
    /** Deterministic per (exam, startAt, offset): also the notification source ID, so a reminder reaches a user at most once. */
    public static UUID reminderKey(UUID examId,Instant startAt,int offset){
        return UUID.nameUUIDFromBytes(("reminder:"+examId+":"+startAt.getEpochSecond()+":"+offset).getBytes(StandardCharsets.UTF_8));
    }

    private Instant now(){return clock.instant();}
    static Timestamp ts(Instant i){return i==null?null:Timestamp.from(i);}
    static Instant instant(Timestamp t){return t==null?null:t.toInstant();}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private List<Integer> read(String s){try{return List.of(json.readValue(s,Integer[].class));}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
}
