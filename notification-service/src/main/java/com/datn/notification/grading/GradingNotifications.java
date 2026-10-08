package com.datn.notification.grading;
import com.datn.notification.delivery.DeliveryQueue;
import com.datn.notification.scheduling.SchedulingMessages.Content;
import com.datn.notification.service.GradingEvents.*;
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
 * Manual-grading notices, recorded in the listener transaction (committed before the broker ack).
 * - Student notices (first release, regrade): inbox row + email/push deliveries, deduplicated per (session, revision).
 * - Teacher review notices: one durable GRADING_REVIEW job; SchedulingJobs resolves OWNER/MANAGER from the current Exam
 *   snapshot (never PROCTOR/Student, never the event) and fans out.
 * Each stream (review / released / regraded) keeps its own revision per session, so a regrade that overtakes the first
 * release never makes the first release look stale. A redelivery with another eventId but the same revision is a no-op.
 * Events carry no recipient lists, scores or private data.
 */
@Service @RequiredArgsConstructor @Transactional
public class GradingNotifications {
    static final Duration TTL=Duration.ofHours(24);
    /** Student notices must survive the student losing their Exam relation: authority is the student's own result. */
    public static final String RESULT_OWNER="RESULT_OWNER";
    private final JdbcTemplate db;private final ObjectMapper json;private final Clock clock;private final DeliveryQueue deliveries;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GradingNotifications.class);

    private enum Stream{REVIEW("review_revision"),RELEASED("released_revision"),REGRADED("regraded_revision");
        final String column;Stream(String column){this.column=column;}}

    public void onReleased(UUID eventId,Released e){
        UUID source=eventId;
        if(e.resultRevision()!=null){
            if(!advance(e.sessionId(),Stream.RELEASED,e.resultRevision()))return;
            source=key("grading-released",e.sessionId(),e.resultRevision());
        }
        studentNotice(source,"grading.result.released",e.studentUserId(),e.sessionId(),e.examId(),GradingMessages.released(e.examTitle(),e.sessionId(),e.gradingMode(),e.resultRevision()));
    }

    public void onRegraded(Regraded e){
        if(!advance(e.sessionId(),Stream.REGRADED,e.resultRevision()))return;
        studentNotice(key("grading-regraded",e.sessionId(),e.resultRevision()),"grading.result.regraded",e.studentUserId(),e.sessionId(),e.examId(),
            GradingMessages.regraded(e.examTitle(),e.sessionId(),e.resultRevision()));
    }

    public void onReviewRequired(ReviewRequired e){
        if(!advance(e.sessionId(),Stream.REVIEW,e.resultRevision()))return;
        Instant now=clock.instant();
        db.update("""
            INSERT INTO scheduling_jobs(id,kind,exam_id,source_id,revision,payload,status,due_at,next_attempt_at,expires_at)
            VALUES(?,'GRADING_REVIEW',?,?,?,CAST(? AS jsonb),'PENDING',?,?,?) ON CONFLICT(kind,source_id) WHERE status <> 'CANCELLED' DO NOTHING""",
            UUID.randomUUID(),e.examId(),key("grading-review",e.sessionId(),e.resultRevision()),e.resultRevision(),write(e),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now.plus(TTL)));
    }

    /** Moves the stream's revision forward under a row lock; false (and no effect) for a duplicate or older revision. */
    private boolean advance(UUID session,Stream stream,long revision){
        db.update("INSERT INTO grading_notice_state(session_id) VALUES(?) ON CONFLICT(session_id) DO NOTHING",session);
        long seen=db.queryForObject("SELECT "+stream.column+" FROM grading_notice_state WHERE session_id=? FOR UPDATE",Long.class,session);
        if(revision<=seen){log.info("Skipped stale or duplicate {} revision {} for session {}",stream,revision,session);return false;}
        db.update("UPDATE grading_notice_state SET "+stream.column+"=?,updated_at=? WHERE session_id=?",revision,Timestamp.from(clock.instant()),session);
        return true;
    }

    private void studentNotice(UUID source,String sourceType,UUID student,UUID session,UUID exam,Content c){
        Instant now=clock.instant();
        var meta=new HashMap<>(c.metadata());meta.put("examId",exam.toString());meta.put("audience",RESULT_OWNER);
        List<UUID> inserted=db.queryForList("""
            INSERT INTO notifications(id,recipient_user_id,type,title,message,entity_type,entity_id,metadata,source_event_id,source_event_type)
            VALUES(?,?,?,?,?,'EXAM_SESSION',?,CAST(? AS jsonb),?,?) ON CONFLICT(source_event_id,recipient_user_id) DO NOTHING RETURNING id""",UUID.class,
            UUID.randomUUID(),student,c.type(),c.title(),c.message(),session,write(meta),source,sourceType);
        if(!inserted.isEmpty())deliveries.enqueue(inserted.getFirst(),student,true,now,now.plus(TTL));
    }

    /** Deterministic per (stream, session, revision): also the inbox dedup key. */
    public static UUID key(String stream,UUID session,long revision){
        return UUID.nameUUIDFromBytes((stream+":"+session+":"+revision).getBytes(StandardCharsets.UTF_8));
    }
    private String write(Object o){try{return json.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
}
