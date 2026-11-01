package com.datn.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * iCalendar attachment for schedule emails, so mail apps offer "Add to calendar". The UID matches exam-service's
 * calendar feed ({@code exam-<id>@examini}) and SEQUENCE is the schedule revision, so a later email or the feed
 * updates the same event instead of adding a duplicate.
 */
final class ExamInvite {
    private ExamInvite() {}
    static final Set<String> TYPES = Set.of("EXAM_SCHEDULED", "EXAM_RESCHEDULED", "EXAM_REMINDER");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** The .ics text, or null when the notification is not about a dated exam. */
    static String ics(String type, UUID examId, Map<String, String> meta, String url, Instant now) {
        if (!TYPES.contains(type) || examId == null || meta.get("startAt") == null) return null;
        Instant start, end;
        try {
            start = Instant.parse(meta.get("startAt"));
            end = meta.get("endAt") == null ? start.plus(Duration.ofHours(1)) : Instant.parse(meta.get("endAt"));
        } catch (RuntimeException e) { return null; }
        if (!end.isAfter(start)) end = start.plus(Duration.ofHours(1));
        String title = meta.getOrDefault("examTitle", "Kỳ thi");
        var sb = new StringBuilder();
        for (String l : new String[]{"BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//EXAMINI//Lich thi//VI", "METHOD:PUBLISH", "BEGIN:VEVENT",
                "UID:exam-" + examId + "@examini", "SEQUENCE:" + seq(meta.get("scheduleRevision")), "DTSTAMP:" + UTC.format(now),
                "DTSTART:" + UTC.format(start), "DTEND:" + UTC.format(end), "SUMMARY:" + text("Thi: " + title),
                "DESCRIPTION:" + text("Xem chi tiết và vào thi: " + url), "URL:" + url, "LOCATION:EXAMINI (trực tuyến)", "STATUS:CONFIRMED",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + text("Thi: " + title), "TRIGGER:-PT15M", "END:VALARM",
                "END:VEVENT", "END:VCALENDAR"}) fold(sb, l);
        return sb.toString();
    }

    private static long seq(String s) { try { return s == null ? 0 : Math.max(0, Long.parseLong(s)); } catch (NumberFormatException e) { return 0; } }
    static String text(String s) { return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n").replace("\r", ""); }
    /** RFC 5545 line folding at 75 octets, never inside a UTF-8 character. */
    static void fold(StringBuilder sb, String line) {
        int octets = 0, from = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i), len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + len > (from == 0 ? 75 : 74)) { sb.append(line, from, i).append("\r\n "); from = i; octets = 0; }
            octets += len; i += Character.charCount(cp);
        }
        sb.append(line, from, line.length()).append("\r\n");
    }
}
