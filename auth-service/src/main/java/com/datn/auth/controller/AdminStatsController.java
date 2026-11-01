package com.datn.auth.controller;

import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import com.datn.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Account figures for system administrators: no personal data, counts only. */
@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
public class AdminStatsController {
    private final AuthService auth;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;

    public record DayCount(LocalDate day, long count) {}
    public record UserStats(long total, long students, long teachers, long admins, long locked, long googleLinked,
                            long activeLast7Days, long activeLast30Days, long newInPeriod, int days, List<DayCount> newPerDay) {}

    @GetMapping("/stats") @Operation(summary = "Quản trị: thống kê tài khoản")
    public UserStats stats(@RequestHeader("Authorization") String authorization, @RequestParam(defaultValue = "30") int days) {
        UUID actor = UUID.fromString(auth.authenticatedUserId(authorization));
        var me = users.findById(actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại"));
        if (me.getSystemPrivilege() != User.SystemPrivilege.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ quản trị viên hệ thống");
        if (days < 1 || days > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "1 <= days <= 365");
        var zone = ZoneId.of("Asia/Ho_Chi_Minh");
        var now = Instant.now();
        var p = new MapSqlParameterSource().addValue("d7", Timestamp.from(now.minus(Duration.ofDays(7))))
                .addValue("d30", Timestamp.from(now.minus(Duration.ofDays(30))))
                .addValue("since", Timestamp.valueOf(LocalDate.now(zone).minusDays(days - 1L).atStartOfDay()));
        var row = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE COALESCE(personal_persona, CASE WHEN role = 'STUDENT' THEN 'STUDENT' ELSE 'TEACHER' END) = 'STUDENT' AND system_privilege <> 'ADMIN') AS students,
                       count(*) FILTER (WHERE COALESCE(personal_persona, CASE WHEN role = 'STUDENT' THEN 'STUDENT' ELSE 'TEACHER' END) = 'TEACHER' AND system_privilege <> 'ADMIN') AS teachers,
                       count(*) FILTER (WHERE system_privilege = 'ADMIN') AS admins,
                       count(*) FILTER (WHERE locked_at IS NOT NULL) AS locked,
                       count(*) FILTER (WHERE google_subject IS NOT NULL) AS google,
                       count(*) FILTER (WHERE last_login_at >= :d7) AS active7,
                       count(*) FILTER (WHERE last_login_at >= :d30) AS active30,
                       count(*) FILTER (WHERE created_at >= :since) AS new_users
                FROM users
                """, p);
        var perDay = new TreeMap<LocalDate, Long>();
        for (int i = days - 1; i >= 0; i--) perDay.put(LocalDate.now(zone).minusDays(i), 0L);
        jdbc.query("SELECT created_at::date AS d, count(*) AS n FROM users WHERE created_at >= :since GROUP BY 1", p,
                rs -> { long n = rs.getLong("n"); perDay.computeIfPresent(rs.getDate("d").toLocalDate(), (k, v) -> v + n); });
        return new UserStats(n(row, "total"), n(row, "students"), n(row, "teachers"), n(row, "admins"), n(row, "locked"), n(row, "google"),
                n(row, "active7"), n(row, "active30"), n(row, "new_users"), days,
                perDay.entrySet().stream().map(e -> new DayCount(e.getKey(), e.getValue())).toList());
    }
    private static long n(Map<String, Object> m, String k) { return ((Number) m.get(k)).longValue(); }
}
