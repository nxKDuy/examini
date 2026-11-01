package com.datn.exam.calendar;

import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.ExamRepository;
import com.datn.exam.service.ExamCore;
import com.datn.exam.service.ScheduleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exams as iCalendar: a download per exam, and a personal read-only subscription feed that calendar apps refresh, so
 * new, moved or cancelled exams show up on the user's phone/computer calendar without opening EXAMINI. The feed URL
 * carries a random secret; only its SHA-256 hash is stored, creating a new link invalidates the old one.
 */
@Service
public class CalendarFeedService {
    static final Duration PAST = Duration.ofDays(60), FUTURE = Duration.ofDays(300);
    private static final Set<String> STAFF = Set.of("OWNER", "MANAGER", "PROCTOR");

    private final JdbcTemplate jdbc;
    private final ScheduleService schedules;
    private final ExamRepository exams;
    private final ExamCore core;
    private final Clock clock;
    private final String appUrl;
    private final SecureRandom random = new SecureRandom();

    public CalendarFeedService(JdbcTemplate jdbc, ScheduleService schedules, ExamRepository exams, ExamCore core, Clock clock,
                               @Value("${exam.calendar.app-url:${APP_BASE_URL:http://localhost:3000}}") String appUrl) {
        this.jdbc = jdbc; this.schedules = schedules; this.exams = exams; this.core = core; this.clock = clock;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    public record FeedStatus(boolean active, Instant createdAt, Instant lastUsedAt) {}
    public record FeedLink(String token, Instant createdAt) {}

    public FeedStatus status(UUID user) {
        var rows = jdbc.query("SELECT created_at, last_used_at FROM calendar_feeds WHERE user_id = ?",
                (rs, i) -> new FeedStatus(true, rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()), user);
        return rows.isEmpty() ? new FeedStatus(false, null, null) : rows.getFirst();
    }

    /** Creates (or replaces) the user's feed secret. The token is returned once and never stored in clear. */
    @Transactional
    public FeedLink create(UUID user, String role) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO calendar_feeds (user_id, token_hash, actor_role, created_at, last_used_at) VALUES (?, ?, ?, ?, NULL)
                ON CONFLICT (user_id) DO UPDATE SET token_hash = EXCLUDED.token_hash, actor_role = EXCLUDED.actor_role,
                    created_at = EXCLUDED.created_at, last_used_at = NULL""", user, hash(token), role, Timestamp.from(now));
        return new FeedLink(token, now);
    }

    public void revoke(UUID user) { jdbc.update("DELETE FROM calendar_feeds WHERE user_id = ?", user); }

    /** The feed for a secret token; empty when the token is unknown or was replaced. */
    public Optional<String> feed(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,64}")) return Optional.empty();
        var owner = jdbc.query("SELECT user_id, actor_role FROM calendar_feeds WHERE token_hash = ?",
                (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)), hash(token));
        if (owner.isEmpty()) return Optional.empty();
        UUID user = owner.getFirst().getKey();
        jdbc.update("UPDATE calendar_feeds SET last_used_at = ? WHERE user_id = ?", Timestamp.from(clock.instant()), user);
        Instant now = clock.instant();
        var items = schedules.calendar(user, owner.getFirst().getValue(), now.minus(PAST), now.plus(FUTURE), false, PageRequest.of(0, 100)).getContent();
        return Optional.of(IcsWriter.calendar("EXAMINI – Lịch thi", events(items), now));
    }

    /** One exam the caller can see, as a single-event calendar file. */
    public String exam(UUID examId, UUID actor) {
        schedules.availability(examId, actor, null); // 404 when the caller may not see it
        Exam e = exams.findById(examId).orElseThrow(() -> ExamCore.error(404, "Exam not found"));
        if (e.getStartAt() == null) throw ExamCore.error(409, "EXAM_NOT_SCHEDULED");
        boolean staff = STAFF.contains(core.role(e, actor));
        return IcsWriter.calendar(e.getTitle(), List.of(event(e, staff)), clock.instant());
    }

    List<IcsWriter.Event> events(List<ScheduleDto.CalendarItem> items) {
        var ids = items.stream().filter(i -> i.startAt() != null).map(ScheduleDto.CalendarItem::examId).toList();
        Map<UUID, Exam> byId = ids.isEmpty() ? Map.of() : exams.findAllById(ids).stream().collect(Collectors.toMap(Exam::getId, Function.identity()));
        return items.stream().filter(i -> i.startAt() != null && byId.containsKey(i.examId()) && !"REVOKED".equals(i.relation()))
                .map(i -> event(byId.get(i.examId()), STAFF.contains(i.relation()))).toList();
    }

    IcsWriter.Event event(Exam e, boolean staff) {
        Instant start = e.getStartAt();
        Instant end = e.getEndAt() != null ? e.getEndAt() : start.plus(Duration.ofMinutes(Math.max(1, e.getDurationMinutes())));
        String link = appUrl + (staff ? "/exams/" + e.getId() : "/exam/lobby/" + e.getId());
        String desc = "Thời lượng làm bài: " + e.getDurationMinutes() + " phút"
                + (e.getEndAt() != null ? "\nKhung giờ thi kết thúc lúc cuối sự kiện." : "")
                + (e.getDescription() == null || e.getDescription().isBlank() ? "" : "\n\n" + e.getDescription().strip())
                + "\n\n" + (staff ? "Quản lý kỳ thi: " : "Vào phòng chờ: ") + link;
        return new IcsWriter.Event("exam-" + e.getId() + "@examini", e.getScheduleRevision(), start, end,
                (staff ? "[Quản lý] " : "Thi: ") + e.getTitle(), desc, link, "EXAMINI (trực tuyến)", false, e.getReminderOffsetsMinutes());
    }

    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
