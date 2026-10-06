package com.datn.notification.service;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Strict v1 payloads for exam.schedule.changed / exam.lifecycle.changed (contracts/notification.md). */
public final class SchedulingEvents {
    public static final int MAX_OFFSETS=10,MAX_OFFSET_MINUTES=43200;
    private SchedulingEvents(){}

    public record Schedule(UUID examId,String examTitle,long scheduleRevision,String changeType,Instant startAt,Instant endAt,String timezone,
                           String deadlineRule,List<Integer> reminderOffsetsMinutes,String examStatus,UUID actorUserId){}
    public record Lifecycle(UUID examId,String examTitle,String status,String trigger,UUID actorUserId,long lifecycleRevision){}
    /** Assignment/membership/follow changed; carries no recipient list (read from the Exam snapshot) and triggers no notification. */
    public record Recipients(UUID examId,long recipientRevision,String cause,UUID actorUserId){}

    public static Schedule schedule(JsonNode p){
        object(p);
        String change=EventCatalog.oneOf(p,"changeType","SCHEDULED","RESCHEDULED","UNSCHEDULED");
        Instant start=instant(p,"startAt"),end=instant(p,"endAt");
        if(change.equals("UNSCHEDULED")?(start!=null || end!=null):(start==null && end==null))throw new IllegalArgumentException("changeType does not match startAt/endAt");
        if(start!=null && end!=null && !end.isAfter(start))throw new IllegalArgumentException("endAt must be after startAt");
        return new Schedule(EventCatalog.uuid(p,"examId"),EventCatalog.title(p),EventCatalog.positive(p,"scheduleRevision"),change,start,end,zone(p),
            EventCatalog.oneOf(p,"deadlineRule","FULL_DURATION","CAPPED_AT_END","REQUIRE_FULL_WINDOW"),offsets(p),
            EventCatalog.oneOf(p,"examStatus","DRAFT","PUBLISHED","OPEN","CLOSED"),optionalUuid(p,"actorUserId"));
    }
    public static Lifecycle lifecycle(JsonNode p){
        object(p);
        String trigger=EventCatalog.oneOf(p,"trigger","AUTO","MANUAL");UUID actor=optionalUuid(p,"actorUserId");
        if(trigger.equals("MANUAL") && actor==null)throw new IllegalArgumentException("MANUAL lifecycle requires actorUserId");
        return new Lifecycle(EventCatalog.uuid(p,"examId"),EventCatalog.title(p),EventCatalog.oneOf(p,"status","PUBLISHED","OPEN","CLOSED"),trigger,actor,EventCatalog.positive(p,"lifecycleRevision"));
    }
    public static Recipients recipients(JsonNode p){
        object(p);
        return new Recipients(EventCatalog.uuid(p,"examId"),EventCatalog.positive(p,"recipientRevision"),
            EventCatalog.oneOf(p,"cause","ASSIGNED","UNASSIGNED","MEMBER_ADDED","MEMBER_REMOVED","FOLLOWED","UNFOLLOWED"),optionalUuid(p,"actorUserId"));
    }
    private static void object(JsonNode p){if(p==null || !p.isObject())throw new IllegalArgumentException("payload must be a JSON object");}
    /** Present-and-null means "no value"; a missing field is a contract violation. */
    static Instant instant(JsonNode p,String field){
        if(!p.has(field))throw new IllegalArgumentException(field+" required (null allowed)");
        if(p.get(field).isNull())return null;
        try{return Instant.parse(EventCatalog.text(p,field));}catch(DateTimeParseException e){throw new IllegalArgumentException(field+" must be an ISO-8601 instant");}
    }
    /** Snapshot variant: missing or null both mean no value. */
    public static Instant optionalInstant(JsonNode p,String field){
        JsonNode v=p.get(field);if(v==null || v.isNull())return null;
        try{return Instant.parse(v.asText());}catch(DateTimeParseException e){throw new IllegalArgumentException(field+" must be an ISO-8601 instant");}
    }
    private static UUID optionalUuid(JsonNode p,String field){
        if(!p.has(field))throw new IllegalArgumentException(field+" required (null allowed)");
        return p.get(field).isNull()?null:EventCatalog.uuid(p,field);
    }
    /** IANA region only (Asia/Ho_Chi_Minh, UTC); raw offsets such as +07:00 would lose DST rules. */
    static String zone(JsonNode p){
        String z=EventCatalog.text(p,"timezone");
        try{if(ZoneId.of(z) instanceof ZoneOffset)throw new IllegalArgumentException("timezone must be an IANA region");}
        catch(DateTimeException e){throw new IllegalArgumentException("timezone must be an IANA region");}
        return z;
    }
    static List<Integer> offsets(JsonNode p){
        JsonNode a=p.get("reminderOffsetsMinutes");
        if(a==null || !a.isArray() || a.size()>MAX_OFFSETS)throw new IllegalArgumentException("reminderOffsetsMinutes must be an array of at most "+MAX_OFFSETS);
        var out=new TreeSet<Integer>(Comparator.reverseOrder());
        for(JsonNode v:a){
            if(!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue()<1 || v.intValue()>MAX_OFFSET_MINUTES)throw new IllegalArgumentException("reminder offset must be an integer 1.."+MAX_OFFSET_MINUTES);
            if(!out.add(v.intValue()))throw new IllegalArgumentException("duplicate reminder offset");
        }
        return List.copyOf(out);
    }
}
