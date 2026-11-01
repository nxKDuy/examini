package com.datn.exam.calendar;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IcsWriterTest {
    static final Instant START = Instant.parse("2030-03-10T01:30:00Z");

    @Test void eventHasUtcTimesEscapedTextAndAlarms() {
        String ics = IcsWriter.calendar("EXAMINI – Lịch thi", List.of(new IcsWriter.Event("exam-1@examini", 3, START, START.plusSeconds(5400),
                "Thi: Toán; giữa kỳ, lớp 12", "Dòng 1\nDòng 2", "http://x/exam/lobby/1", "EXAMINI", false, List.of(1440, 15, 15))), START);
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertTrue(ics.contains("DTSTART:20300310T013000Z\r\n"));
        assertTrue(ics.contains("DTEND:20300310T030000Z\r\n"));
        assertTrue(ics.contains("SEQUENCE:3\r\n"));
        assertTrue(ics.contains("SUMMARY:Thi: Toán\\; giữa kỳ\\, lớp 12\r\n"));
        assertTrue(ics.contains("DESCRIPTION:Dòng 1\\nDòng 2\r\n"));
        assertEquals(2, ics.split("BEGIN:VALARM", -1).length - 1, "duplicate offsets collapse");
        assertTrue(ics.contains("TRIGGER:-PT1440M") && ics.contains("TRIGGER:-PT15M"));
        assertFalse(ics.contains("\n\n"));
    }

    @Test void longLinesFoldWithinSeventyFiveOctetsWithoutBreakingCharacters() {
        String title = "Kiểm tra cuối kỳ môn Ngữ văn — phần đọc hiểu và nghị luận xã hội dành cho học sinh lớp mười hai";
        String ics = IcsWriter.calendar("X", List.of(new IcsWriter.Event("u", 0, START, null, title, null, null, null, false, List.of())), START);
        for (String line : ics.split("\r\n")) assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, line);
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("SUMMARY:" + title));
        assertTrue(ics.contains("DTEND:20300310T023000Z"), "no end → one hour");
    }

    @Test void unscheduledExamsAreSkippedAndCancelledOnesHaveNoAlarm() {
        String ics = IcsWriter.calendar("X", List.of(new IcsWriter.Event("a", 0, null, null, "A", null, null, null, false, List.of(15)),
                new IcsWriter.Event("b", 0, START, null, "B", null, null, null, true, List.of(15))), START);
        assertFalse(ics.contains("UID:a"));
        assertTrue(ics.contains("STATUS:CANCELLED"));
        assertFalse(ics.contains("VALARM"));
    }
}
