package com.datn.notification.service;
import com.datn.notification.dto.NotificationDto.Draft;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.util.*;

/**
 * Strict v1 mapping of exam.participant.changed and exam.attempt.invalidated (contracts/participant-attempt.md) to
 * single-recipient Student notices. The Student is the payload's userId. The SPA route is generated here from the Exam or
 * session ID; no producer link exists or is used. Only the public reason is shown; actor IDs, counts, revisions, email and
 * private notes are validated where required and never stored. JOINED produces no notice (the Student did it themselves).
 */
public final class ParticipantEvents {
    public static final String CHANGED="exam.participant.changed",ATTEMPT_INVALIDATED="exam.attempt.invalidated";
    public static final List<String> TYPES=List.of(CHANGED,ATTEMPT_INVALIDATED);
    public static final int REASON_LIMIT=300,MAX_QUANTITY=100;
    private static final List<String> SESSION_STATUSES=List.of("IN_PROGRESS","SUBMITTED","AUTO_SUBMITTED","CANCELLED");
    private ParticipantEvents(){}

    public static boolean handles(String type){return TYPES.contains(type);}

    /** The notice for this event, or null when the event is valid but intentionally silent (JOINED). */
    public static Draft draft(String type,JsonNode p){
        if(p==null || !p.isObject())throw new IllegalArgumentException("payload must be a JSON object");
        return type.equals(CHANGED)?changed(p):invalidated(p);
    }

    private static Draft changed(JsonNode p){
        UUID exam=EventCatalog.uuid(p,"examId"),student=EventCatalog.uuid(p,"userId");String title=EventCatalog.title(p);
        String cause=EventCatalog.oneOf(p,"cause","ASSIGNED","REINSTATED","JOINED","REVOKED","ATTEMPT_GRANTED");
        optionalUuid(p,"actorUserId");
        // Required by the contract; used by Analytics, only validated here.
        EventCatalog.integer(p,"recipientRevision");nonNegative(p,"recipientRevision");
        for(String count:List.of("registeredCount","eligibleCount","revokedCount")){EventCatalog.integer(p,count);nonNegative(p,count);}
        Long quantity=optionalQuantity(p);String reason=publicReason(p,"publicReason");
        if(cause.equals("ATTEMPT_GRANTED") && quantity==null)throw new IllegalArgumentException("quantity required for ATTEMPT_GRANTED");
        if(cause.equals("JOINED"))return null;
        String q="\""+title+"\"",why=reason==null?"":" Lý do: "+reason;
        var meta=new LinkedHashMap<String,String>();meta.put("examId",exam.toString());meta.put("examTitle",title);meta.put("cause",cause);
        String type,heading,message,link="/exam/lobby/"+exam;
        switch(cause){
            case "ASSIGNED" -> {type="PARTICIPANT_ASSIGNED";heading="Bạn được giao kỳ thi";message="Bạn được giao kỳ thi "+q+".";}
            case "REINSTATED" -> {type="PARTICIPANT_REINSTATED";heading="Quyền dự thi được khôi phục";message="Quyền dự thi kỳ thi "+q+" của bạn đã được khôi phục.";}
            case "REVOKED" -> {type="PARTICIPANT_REVOKED";heading="Quyền dự thi bị thu hồi";message="Quyền dự thi kỳ thi "+q+" của bạn đã bị thu hồi."+why;link="/my-exams";}
            default -> {type="ATTEMPT_GRANTED";heading="Được cấp thêm lượt làm bài";message="Bạn được cấp thêm "+quantity+" lượt làm bài kỳ thi "+q+"."+why;meta.put("quantity",String.valueOf(quantity));}
        }
        meta.put("link",link);
        return new Draft(student,type,heading,message,"EXAM",exam,Map.copyOf(meta));
    }

    private static Draft invalidated(JsonNode p){
        UUID session=EventCatalog.uuid(p,"sessionId"),exam=EventCatalog.uuid(p,"examId"),student=EventCatalog.uuid(p,"userId");
        long attempt=EventCatalog.positive(p,"attemptNumber");String status=EventCatalog.oneOf(p,"status",SESSION_STATUSES.toArray(String[]::new));
        optionalUuid(p,"actor");
        try{Instant.parse(EventCatalog.text(p,"invalidatedAt"));}catch(DateTimeParseException e){throw new IllegalArgumentException("invalidatedAt must be an ISO-8601 instant");}
        String reason=publicReason(p,"reason");
        // An attempt that was submitted has a result page; a cancelled in-progress attempt only has the Exam page.
        String link=status.equals("CANCELLED") || status.equals("IN_PROGRESS")?"/exam/lobby/"+exam:"/exam/result/"+session;
        var meta=new LinkedHashMap<String,String>();meta.put("examId",exam.toString());meta.put("sessionId",session.toString());
        meta.put("attemptNumber",String.valueOf(attempt));meta.put("status",status);meta.put("link",link);
        return new Draft(student,"ATTEMPT_INVALIDATED","Lượt làm bài không hợp lệ",
            "Lượt làm bài số "+attempt+" của bạn đã bị vô hiệu hóa."+(reason==null?"":" Lý do: "+reason),"EXAM_SESSION",session,Map.copyOf(meta));
    }

    /** Optional free text for display: absent/null/blank means none; control characters are replaced and the text is truncated. */
    static String publicReason(JsonNode p,String field){
        JsonNode v=p.get(field);if(v==null || v.isNull())return null;
        if(!v.isTextual())throw new IllegalArgumentException(field+" must be text");
        String t=v.asText().replaceAll("\\p{Cntrl}"," ").strip();
        if(t.isEmpty())return null;
        return t.length()>REASON_LIMIT?t.substring(0,REASON_LIMIT-1)+"…":t;
    }
    private static void optionalUuid(JsonNode p,String field){JsonNode v=p.get(field);if(v!=null && !v.isNull())EventCatalog.uuid(p,field);}
    private static Long optionalQuantity(JsonNode p){
        JsonNode v=p.get("quantity");if(v==null || v.isNull())return null;
        long q=EventCatalog.integer(p,"quantity");if(q<1 || q>MAX_QUANTITY)throw new IllegalArgumentException("quantity must be 1.."+MAX_QUANTITY);return q;
    }
    private static void nonNegative(JsonNode p,String field){if(p.get(field).longValue()<0)throw new IllegalArgumentException(field+" must not be negative");}
}
