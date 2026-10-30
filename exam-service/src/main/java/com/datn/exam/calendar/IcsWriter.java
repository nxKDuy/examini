package com.datn.exam.calendar;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Minimal RFC 5545 (iCalendar) writer for exam events: CRLF line ends, 75-octet folding, escaped text, UTC times.
 * Google Calendar, Apple Calendar and Outlook import the result and refresh subscriptions of it.
 */
public final class IcsWriter {
    private IcsWriter() {}
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** One exam occurrence. {@code reminders} are minutes before start (calendar apps may ignore them in feeds). */
    public record Event(String uid, long sequence, Instant start, Instant end, String summary, String description,
                        String url, String location, boolean cancelled, List<Integer> reminders) {}

    public static String calendar(String name, List<Event> events, Instant now) {
        var sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//EXAMINI//Lich thi//VI");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:" + text(name));
        line(sb, "X-WR-TIMEZONE:Asia/Ho_Chi_Minh");
        line(sb, "REFRESH-INTERVAL;VALUE=DURATION:PT1H");
        line(sb, "X-PUBLISHED-TTL:PT1H");
        for (Event e : events) {
            if (e.start() == null) continue;
            Instant end = e.end() != null && e.end().isAfter(e.start()) ? e.end() : e.start().plusSeconds(3600);
            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:" + e.uid());
            line(sb, "SEQUENCE:" + Math.max(0, e.sequence()));
            line(sb, "DTSTAMP:" + UTC.format(now));
            line(sb, "DTSTART:" + UTC.format(e.start()));
            line(sb, "DTEND:" + UTC.format(end));
            line(sb, "SUMMARY:" + text(e.summary()));
            if (e.description() != null) line(sb, "DESCRIPTION:" + text(e.description()));
            if (e.location() != null) line(sb, "LOCATION:" + text(e.location()));
            if (e.url() != null) line(sb, "URL:" + e.url());
            line(sb, "STATUS:" + (e.cancelled() ? "CANCELLED" : "CONFIRMED"));
            line(sb, "TRANSP:OPAQUE");
            if (!e.cancelled() && e.reminders() != null)
                for (Integer m : e.reminders().stream().filter(m -> m != null && m > 0).distinct().limit(3).toList()) {
                    line(sb, "BEGIN:VALARM");
                    line(sb, "ACTION:DISPLAY");
                    line(sb, "DESCRIPTION:" + text(e.summary()));
                    line(sb, "TRIGGER:-PT" + m + "M");
                    line(sb, "END:VALARM");
                }
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** TEXT value escaping (RFC 5545 §3.3.11). */
    static String text(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "");
    }

    /** Appends a content line folded at 75 octets without splitting a UTF-8 character. */
    static void line(StringBuilder sb, String content) {
        int octets = 0;
        int start = 0;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            int limit = start == 0 ? 75 : 74; // continuation lines start with one space
            if (octets + len > limit) {
                sb.append(content, start, i).append("\r\n ");
                start = i;
                octets = 0;
            }
            octets += len;
            i += Character.charCount(cp);
        }
        sb.append(content, start, content.length()).append("\r\n");
    }
}
