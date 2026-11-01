package com.datn.questionbank.controller;

import com.datn.questionbank.security.QuestionActor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Question bank figures for system administrators, including how much AI is used to write questions. */
@RestController
@RequestMapping("/questions/admin")
@RequiredArgsConstructor
public class QuestionAdminStatsController {
    private final NamedParameterJdbcTemplate jdbc;

    public record DayCount(LocalDate day, long created, long aiGenerated) {}
    public record AiUsage(long jobs, long jobsFailed, long draftsGenerated, long draftsImported, Map<String, Long> byModel) {}
    public record QuestionStats(long total, Map<String, Long> byStatus, Map<String, Long> byType, Map<String, Long> byDifficulty,
                                Map<String, Long> byCreationMethod, long withMedia, long publicPosts, long importJobs,
                                AiUsage ai, int days, List<DayCount> perDay) {}

    @GetMapping("/stats")
    public QuestionStats stats(HttpServletRequest req, @RequestParam(defaultValue = "30") int days) {
        Object a = req.getAttribute(QuestionActor.class.getName());
        if (!(a instanceof QuestionActor actor) || !actor.systemAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Yêu cầu signed systemPrivilege=ADMIN");
        if (days < 1 || days > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "1 <= days <= 365");
        var zone = ZoneId.of("Asia/Ho_Chi_Minh");
        var p = new MapSqlParameterSource("since", Timestamp.valueOf(LocalDate.now(zone).minusDays(days - 1L).atStartOfDay()));
        var byStatus = group("SELECT status AS k, count(*) AS n FROM questions GROUP BY 1", p);
        var byType = group("SELECT question_type AS k, count(*) AS n FROM questions WHERE status <> 'ARCHIVED' GROUP BY 1", p);
        var byDifficulty = group("SELECT difficulty_level AS k, count(*) AS n FROM questions WHERE status <> 'ARCHIVED' GROUP BY 1", p);
        var byMethod = group("SELECT creation_method AS k, count(*) AS n FROM questions GROUP BY 1", p);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long media = one("SELECT count(*) FROM questions WHERE presentation IS NOT NULL", p);
        long posts = one("SELECT count(*) FROM public_questions", p);
        long jobs = one("SELECT count(*) FROM question_import_jobs WHERE file_kind <> 'AI'", p);
        long aiJobs = one("SELECT count(*) FROM question_import_jobs WHERE file_kind = 'AI'", p);
        long aiFailed = one("SELECT count(*) FROM question_import_jobs WHERE file_kind = 'AI' AND status = 'FAILED'", p);
        long drafts = one("SELECT count(*) FROM question_import_items i JOIN question_import_jobs j ON j.id = i.job_id WHERE j.file_kind = 'AI'", p);
        long imported = one("SELECT count(*) FROM question_import_items i JOIN question_import_jobs j ON j.id = i.job_id WHERE j.file_kind = 'AI' AND i.imported_question_id IS NOT NULL", p);
        var byModel = group("SELECT COALESCE(generation::jsonb ->> 'model', 'không rõ') AS k, count(*) AS n FROM question_import_jobs WHERE file_kind = 'AI' GROUP BY 1", p);
        var perDay = new TreeMap<LocalDate, long[]>();
        for (int i = days - 1; i >= 0; i--) perDay.put(LocalDate.now(zone).minusDays(i), new long[2]);
        jdbc.query("SELECT created_at::date AS d, count(*) AS n, count(*) FILTER (WHERE creation_method = 'AI_GENERATED') AS ai FROM questions WHERE created_at >= :since GROUP BY 1", p,
                rs -> { var v = perDay.get(rs.getDate("d").toLocalDate()); if (v != null) { v[0] = rs.getLong("n"); v[1] = rs.getLong("ai"); } });
        return new QuestionStats(total, byStatus, byType, byDifficulty, byMethod, media, posts, jobs, new AiUsage(aiJobs, aiFailed, drafts, imported, byModel), days,
                perDay.entrySet().stream().map(e -> new DayCount(e.getKey(), e.getValue()[0], e.getValue()[1])).toList());
    }
    private Map<String, Long> group(String sql, MapSqlParameterSource p) {
        var out = new TreeMap<String, Long>();
        jdbc.query(sql, p, rs -> { out.put(String.valueOf(rs.getString("k")), rs.getLong("n")); });
        return out;
    }
    private long one(String sql, MapSqlParameterSource p) { Long n = jdbc.queryForObject(sql, p, Long.class); return n == null ? 0 : n; }
}
