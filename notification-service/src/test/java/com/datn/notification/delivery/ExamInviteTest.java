package com.datn.notification.delivery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ExamInviteTest {
    static final UUID EXAM = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final Instant NOW = Instant.parse("2030-03-01T00:00:00Z");

    @Test void scheduledExamBecomesOneUpdatableEvent() {
        String ics = ExamInvite.ics("EXAM_RESCHEDULED", EXAM, Map.of("examTitle", "Toán 12; đề A, lần 2", "startAt", "2030-03-10T01:30:00Z",
                "endAt", "2030-03-10T03:00:00Z", "scheduleRevision", "4"), "http://app.test/exam/lobby/" + EXAM, NOW);
        assertNotNull(ics);
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("UID:exam-" + EXAM + "@examini\r\n"));
        assertTrue(unfolded.contains("SEQUENCE:4\r\n"));
        assertTrue(unfolded.contains("DTSTART:20300310T013000Z\r\nDTEND:20300310T030000Z\r\n"));
        assertTrue(unfolded.contains("SUMMARY:Thi: Toán 12\\; đề A\\, lần 2\r\n"));
        for (String line : ics.split("\r\n")) assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, line);
    }

    @Test void onlyDatedScheduleNoticesGetAnInvite() {
        var meta = Map.of("examTitle", "X", "startAt", "2030-03-10T01:30:00Z");
        assertNotNull(ExamInvite.ics("EXAM_REMINDER", EXAM, meta, "u", NOW));
        assertTrue(ExamInvite.ics("EXAM_SCHEDULED", EXAM, meta, "u", NOW).contains("DTEND:20300310T023000Z"), "no end → one hour");
        assertNull(ExamInvite.ics("EXAM_OPENED", EXAM, meta, "u", NOW));
        assertNull(ExamInvite.ics("EXAM_UNSCHEDULED", EXAM, Map.of("examTitle", "X"), "u", NOW));
        assertNull(ExamInvite.ics("RESULT_RELEASED", EXAM, meta, "u", NOW));
        assertNull(ExamInvite.ics("EXAM_SCHEDULED", null, meta, "u", NOW));
        assertNull(ExamInvite.ics("EXAM_SCHEDULED", EXAM, Map.of("startAt", "not a time"), "u", NOW));
    }
}
