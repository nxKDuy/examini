package com.datn.exam;

import com.datn.exam.entity.Exam;
import com.datn.exam.service.ScheduleRules;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleRulesTest {
    @Test void permitsUnscheduledAndPartialFullDurationSchedulesWithReminderDefaults() {
        assertDoesNotThrow(() -> ScheduleRules.validate(null,null,"UTC",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(1440,15),60));
        assertDoesNotThrow(() -> ScheduleRules.validate(Instant.parse("2030-01-01T10:00:00Z"),null,"UTC",Exam.DeadlineRule.FULL_DURATION,true,false,List.of(1440,15),60));
        assertDoesNotThrow(() -> ScheduleRules.validate(null,Instant.parse("2030-01-01T11:00:00Z"),"UTC",Exam.DeadlineRule.FULL_DURATION,false,true,List.of(1440,15),60));
    }

    @Test void validatesEndRequirementAndAutomaticTransitionBounds() {
        assertThrows(RuntimeException.class,() -> ScheduleRules.validate(null,null,"UTC",Exam.DeadlineRule.CAPPED_AT_END,false,false,List.of(),60));
        assertThrows(RuntimeException.class,() -> ScheduleRules.validate(null,null,"UTC",Exam.DeadlineRule.REQUIRE_FULL_WINDOW,false,false,List.of(),60));
        assertThrows(RuntimeException.class,() -> ScheduleRules.validate(null,null,"UTC",Exam.DeadlineRule.FULL_DURATION,true,false,List.of(),60));
        assertThrows(RuntimeException.class,() -> ScheduleRules.validate(null,null,"UTC",Exam.DeadlineRule.FULL_DURATION,false,true,List.of(),60));
        assertThrows(RuntimeException.class,() -> ScheduleRules.validate(Instant.parse("2030-01-01T12:00:00Z"),Instant.parse("2030-01-01T12:00:00Z"),"UTC",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(),60));
    }

    @Test void usesHalfOpenWindowAndKeepsExistingDeadlineFormula() {
        Instant start=Instant.parse("2030-01-01T10:00:00Z"),end=Instant.parse("2030-01-01T11:00:00Z");
        Exam exam=Exam.builder().status(Exam.Status.OPEN).startAt(start).endAt(end).deadlineRule(Exam.DeadlineRule.CAPPED_AT_END).durationMinutes(60).build();
        assertTrue(ScheduleRules.inStartWindow(exam,start));
        assertFalse(ScheduleRules.inStartWindow(exam,end));
        assertEquals(end,ScheduleRules.deadline(start,60,end,Exam.DeadlineRule.CAPPED_AT_END));
        assertEquals(start.plusSeconds(3600),ScheduleRules.deadline(start,60,end,Exam.DeadlineRule.FULL_DURATION));
    }

    @Test void rejectsDaylightSavingGapsAndRequiresAnOffsetValidForExamZone() {
        ZoneId zone=ZoneId.of("America/Los_Angeles");
        assertThrows(RuntimeException.class,() -> ScheduleRules.validateLocal(OffsetDateTime.parse("2026-03-08T02:30:00-08:00"),zone,"startAt"));
        assertThrows(RuntimeException.class,() -> ScheduleRules.validateLocal(OffsetDateTime.parse("2026-01-08T02:30:00-05:00"),zone,"startAt"));
        assertDoesNotThrow(() -> ScheduleRules.validateLocal(OffsetDateTime.parse("2026-11-01T01:30:00-07:00"),zone,"startAt"));
        assertDoesNotThrow(() -> ScheduleRules.validateLocal(OffsetDateTime.parse("2026-11-01T01:30:00-08:00"),zone,"startAt"));
    }
}
