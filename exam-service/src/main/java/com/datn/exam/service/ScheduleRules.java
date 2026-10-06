package com.datn.exam.service;

import com.datn.exam.entity.Exam;
import java.time.*;
import java.util.List;

public final class ScheduleRules {
    private ScheduleRules() {}

    public static void validate(Instant startAt,Instant endAt,String timezone,Exam.DeadlineRule rule,
                                boolean autoOpen,boolean autoClose,List<Integer> reminders,int durationMinutes) {
        if(durationMinutes<1 || rule==null || timezone==null) throw invalid("Duration, timezone and deadline rule are required");
        ZoneId zone;
        try { zone=ZoneId.of(timezone); }
        catch(DateTimeException ex) { throw invalid("timezone must be an IANA region or UTC"); }
        if(!"UTC".equals(timezone) && !timezone.contains("/") || !ZoneId.getAvailableZoneIds().contains(timezone) && !"UTC".equals(timezone))
            throw invalid("timezone must be an IANA region or UTC");
        if(startAt!=null && endAt!=null && !startAt.isBefore(endAt)) throw invalid("endAt must be after startAt");
        if(rule!=Exam.DeadlineRule.FULL_DURATION && endAt==null) throw invalid("This deadline rule requires endAt");
        if(autoOpen && startAt==null) throw invalid("autoOpen requires startAt");
        if(autoClose && endAt==null) throw invalid("autoClose requires endAt");
        if(reminders==null || reminders.size()>10 || reminders.stream().anyMatch(v->v==null || v<1 || v>43200) ||
                reminders.stream().distinct().count()!=reminders.size()) throw invalid("Reminder offsets must be 1..43200 minutes, unique, at most 10");
    }

    public static void validateLocal(OffsetDateTime value,ZoneId zone,String name) {
        if(value==null) return;
        List<ZoneOffset> offsets=zone.getRules().getValidOffsets(value.toLocalDateTime());
        if(offsets.isEmpty()) throw invalid(name+" falls in a daylight-saving gap");
        if(!offsets.contains(value.getOffset())) throw invalid(name+" offset does not match timezone");
    }

    public static boolean inStartWindow(Exam e,Instant now) {
        return e.getStatus()==Exam.Status.OPEN && (e.getStartAt()==null || !now.isBefore(e.getStartAt())) &&
                (e.getEndAt()==null || now.isBefore(e.getEndAt()));
    }

    public static Instant deadline(Instant startedAt,int durationMinutes,Instant endAt,Exam.DeadlineRule rule) {
        Instant full=startedAt.plusSeconds(durationMinutes*60L);
        return rule==Exam.DeadlineRule.CAPPED_AT_END && endAt.isBefore(full) ? endAt : full;
    }

    private static org.springframework.web.server.ResponseStatusException invalid(String message) {
        return ExamCore.error(400,message);
    }
}
