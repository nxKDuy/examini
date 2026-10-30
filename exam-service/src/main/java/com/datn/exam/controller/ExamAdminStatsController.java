package com.datn.exam.controller;

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

/** Exam counts for system administrators (systemPrivilege ADMIN); titles only for the ids the dashboard asks about. */
@RestController
@RequestMapping("/exams/admin")
@RequiredArgsConstructor
public class ExamAdminStatsController {
    private final NamedParameterJdbcTemplate jdbc;

    public record DayCount(LocalDate day, long count) {}
    public record ExamStats(long total, Map<String, Long> byStatus, Map<String, Long> byType, long withCamera, long createdInPeriod,
                            int days, List<DayCount> createdPerDay, Map<UUID, String> titles) {}

    @GetMapping("/stats")
    public ExamStats stats(@RequestAttribute String systemPrivilege, @RequestParam(defaultValue = "30") int days,
                           @RequestParam(required = false) List<UUID> ids) {
        if (!"ADMIN".equals(systemPrivilege)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator privilege required");
        if (days < 1 || days > 365 || (ids != null && ids.size() > 50)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "1 <= days <= 365, at most 50 ids");
        var zone = ZoneId.of("Asia/Ho_Chi_Minh");
        var p = new MapSqlParameterSource("since", Timestamp.valueOf(LocalDate.now(zone).minusDays(days - 1L).atStartOfDay()));
        var byStatus = new TreeMap<String, Long>(); var byType = new TreeMap<String, Long>();
        jdbc.query("SELECT status, count(*) AS n FROM exams GROUP BY status", p, rs -> { byStatus.put(rs.getString("status"), rs.getLong("n")); });
        jdbc.query("SELECT type, count(*) AS n FROM exams GROUP BY type", p, rs -> { byType.put(rs.getString("type"), rs.getLong("n")); });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        Long camera = jdbc.queryForObject("SELECT count(*) FROM exams WHERE (integrity_policy ->> 'requireCamera')::boolean IS TRUE", p, Long.class);
        var perDay = new TreeMap<LocalDate, Long>();
        for (int i = days - 1; i >= 0; i--) perDay.put(LocalDate.now(zone).minusDays(i), 0L);
        jdbc.query("SELECT created_at::date AS d, count(*) AS n FROM exams WHERE created_at >= :since GROUP BY 1", p,
                rs -> { long n = rs.getLong("n"); perDay.computeIfPresent(rs.getDate("d").toLocalDate(), (k, v) -> v + n); });
        var titles = new LinkedHashMap<UUID, String>();
        if (ids != null && !ids.isEmpty())
            jdbc.query("SELECT id, title FROM exams WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids),
                    rs -> { titles.put(rs.getObject("id", UUID.class), rs.getString("title")); });
        return new ExamStats(total, byStatus, byType, camera == null ? 0 : camera, perDay.values().stream().mapToLong(Long::longValue).sum(), days,
                perDay.entrySet().stream().map(e -> new DayCount(e.getKey(), e.getValue())).toList(), titles);
    }
}
