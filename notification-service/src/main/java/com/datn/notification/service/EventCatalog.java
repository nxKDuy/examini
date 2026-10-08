package com.datn.notification.service;
import com.datn.notification.dto.NotificationDto.Draft;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Maps version-1 source-domain events (contracts/notification.md) to in-app notifications.
 * Notification owns only the wording; the business facts stay with their producers.
 * Invalid input throws IllegalArgumentException so the message is dead-lettered, never guessed.
 */
public final class EventCatalog {
    public static final String QUESTION_REVIEWED="question.reviewed",EXAM_MEMBER_INVITED="exam.member.invited",
        EXAM_PARTICIPANT_ASSIGNED="exam.participant.assigned",SESSION_SUBMITTED="session.submitted",GRADING_RESULT_RELEASED="grading.result.released";
    /** Manual grading (contracts/manual-grading-scope.md): additive v1 events handled by GradingNotifications. */
    public static final String GRADING_REVIEW_REQUIRED="grading.review.required",GRADING_RESULT_REGRADED="grading.result.regraded";
    /** Exam-scoped Scheduling events: no recipient in the payload, fanned out by SchedulingJobs. */
    public static final String EXAM_SCHEDULE_CHANGED="exam.schedule.changed",EXAM_LIFECYCLE_CHANGED="exam.lifecycle.changed",EXAM_RECIPIENTS_CHANGED="exam.recipients.changed";
    public static final List<String> SCHEDULING_TYPES=List.of(EXAM_SCHEDULE_CHANGED,EXAM_LIFECYCLE_CHANGED,EXAM_RECIPIENTS_CHANGED);
    public static final List<String> EVENT_TYPES=List.of(QUESTION_REVIEWED,EXAM_MEMBER_INVITED,EXAM_PARTICIPANT_ASSIGNED,SESSION_SUBMITTED,GRADING_RESULT_RELEASED,
        EXAM_SCHEDULE_CHANGED,EXAM_LIFECYCLE_CHANGED,EXAM_RECIPIENTS_CHANGED,GRADING_REVIEW_REQUIRED,GRADING_RESULT_REGRADED,
        // Organization events (contracts/organization-events.md), single-recipient inbox notices.
        OrganizationEvents.INVITATION_CREATED,OrganizationEvents.INVITATION_ACCEPTED,OrganizationEvents.INVITATION_DECLINED,OrganizationEvents.INVITATION_REVOKED,
        OrganizationEvents.INVITATION_EXPIRED,OrganizationEvents.MEMBERSHIP_REVOKED,OrganizationEvents.ROLE_CHANGED,OrganizationEvents.OWNERSHIP_TRANSFERRED,
        // Participant / attempt events (contracts/participant-attempt.md). The older exam.participant.assigned stays accepted.
        ParticipantEvents.CHANGED,ParticipantEvents.ATTEMPT_INVALIDATED);
    private static final int TITLE_LIMIT=150;
    private EventCatalog(){}

    /** Validated version-1 envelope. Scheduling events carry their validated payload instead of a single-recipient draft. */
    public record Event(UUID eventId,String eventType,Instant occurredAt,Draft draft,Object scheduling){}

    /** Strict: eventVersion must be the JSON integer 1 and occurredAt an ISO-8601 instant; nothing is coerced. */
    public static Event envelope(JsonNode e){
        if(e==null || !e.isObject())throw new IllegalArgumentException("Envelope must be a JSON object");
        UUID id=uuid(e,"eventId");String type=text(e,"eventType");
        if(!EVENT_TYPES.contains(type))throw new IllegalArgumentException("Unsupported event type");
        if(integer(e,"eventVersion")!=1)throw new IllegalArgumentException("Unsupported event version");
        Instant occurred;try{occurred=Instant.parse(text(e,"occurredAt"));}catch(DateTimeParseException x){throw new IllegalArgumentException("occurredAt must be an ISO-8601 instant");}
        JsonNode payload=e.get("payload");
        if(payload==null || !payload.isObject())throw new IllegalArgumentException("payload must be a JSON object");
        if(type.equals(EXAM_SCHEDULE_CHANGED))return new Event(id,type,occurred,null,SchedulingEvents.schedule(payload));
        if(type.equals(EXAM_LIFECYCLE_CHANGED))return new Event(id,type,occurred,null,SchedulingEvents.lifecycle(payload));
        if(type.equals(EXAM_RECIPIENTS_CHANGED))return new Event(id,type,occurred,null,SchedulingEvents.recipients(payload));
        if(type.equals(GRADING_RESULT_RELEASED))return new Event(id,type,occurred,null,GradingEvents.released(payload));
        if(type.equals(GRADING_RESULT_REGRADED))return new Event(id,type,occurred,null,GradingEvents.regraded(payload));
        if(type.equals(GRADING_REVIEW_REQUIRED))return new Event(id,type,occurred,null,GradingEvents.reviewRequired(payload));
        return new Event(id,type,occurred,draft(type,payload),null);
    }

    public static Draft draft(String type,JsonNode p){
        return switch(type){
            case QUESTION_REVIEWED -> {
                String decision=oneOf(p,"decision","APPROVED","REJECTED");String version=String.valueOf(positive(p,"questionVersion"));
                boolean approved=decision.equals("APPROVED");
                yield new Draft(uuid(p,"ownerUserId"),approved?"QUESTION_APPROVED":"QUESTION_REJECTED",approved?"Câu hỏi đã được duyệt":"Câu hỏi bị từ chối",
                    approved?"Phiên bản "+version+" của câu hỏi đã được phê duyệt.":"Phiên bản "+version+" của câu hỏi bị từ chối. Xem ghi chú duyệt trong Ngân hàng câu hỏi.",
                    "QUESTION",uuid(p,"questionId"),Map.of("questionVersion",version,"decision",decision));
            }
            case EXAM_MEMBER_INVITED -> {
                String role=oneOf(p,"role","MANAGER","PROCTOR"),title=title(p);
                yield new Draft(uuid(p,"userId"),"EXAM_MEMBER_INVITED","Lời mời cộng tác kỳ thi",
                    "Bạn được mời làm "+(role.equals("MANAGER")?"Quản lý":"Giám thị")+" của kỳ thi \""+title+"\". Hãy chấp nhận lời mời để tham gia.",
                    "EXAM",uuid(p,"examId"),Map.of("examTitle",title,"role",role));
            }
            case EXAM_PARTICIPANT_ASSIGNED -> {
                String title=title(p);
                yield new Draft(uuid(p,"userId"),"EXAM_ASSIGNED","Bạn được giao kỳ thi mới","Bạn được giao kỳ thi \""+title+"\".",
                    "EXAM",uuid(p,"examId"),Map.of("examTitle",title));
            }
            case SESSION_SUBMITTED -> {
                String kind=oneOf(p,"submissionType","SUBMITTED","AUTO_SUBMITTED"),title=title(p);
                yield new Draft(uuid(p,"studentUserId"),"SUBMISSION_RECEIVED","Đã nhận bài làm",
                    (kind.equals("AUTO_SUBMITTED")?"Hết giờ, bài làm kỳ thi \""+title+"\" đã được tự động nộp.":"Bài làm kỳ thi \""+title+"\" đã được nộp thành công."),
                    "EXAM_SESSION",uuid(p,"sessionId"),Map.of("examId",uuid(p,"examId").toString(),"examTitle",title,"submissionType",kind));
            }
            default -> {
                if(OrganizationEvents.handles(type))yield OrganizationEvents.draft(type,p);
                if(ParticipantEvents.handles(type))yield ParticipantEvents.draft(type,p);
                throw new IllegalArgumentException("Unsupported event type");
            }
        };
    }
    static UUID uuid(JsonNode p,String field){
        try{return UUID.fromString(text(p,field));}catch(IllegalArgumentException e){throw new IllegalArgumentException(field+" must be a UUID");}
    }
    static String text(JsonNode p,String field){
        JsonNode v=p==null?null:p.get(field);
        if(v==null || !v.isTextual() || v.asText().isBlank())throw new IllegalArgumentException(field+" required");
        return v.asText().strip();
    }
    static String oneOf(JsonNode p,String field,String... allowed){
        String v=text(p,field);if(!Arrays.asList(allowed).contains(v))throw new IllegalArgumentException(field+" unsupported");return v;
    }
    /** JSON integer only: rejects 1.5, 1.0, "1", booleans and out-of-range values instead of coercing them. */
    static long integer(JsonNode p,String field){
        JsonNode v=p==null?null:p.get(field);
        if(v==null || !v.isIntegralNumber() || !v.canConvertToLong())throw new IllegalArgumentException(field+" must be an integer");return v.longValue();
    }
    static long positive(JsonNode p,String field){
        long v=integer(p,field);if(v<1)throw new IllegalArgumentException(field+" must be positive");return v;
    }
    static String title(JsonNode p){
        String t=text(p,"examTitle").replaceAll("\\p{Cntrl}"," ");
        return t.length()>TITLE_LIMIT?t.substring(0,TITLE_LIMIT-1)+"…":t;
    }
}
