package com.datn.analytics.query;

import com.datn.analytics.query.AnalyticsDto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregations run in PostgreSQL over the per-exam indexed projections. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExamAnalyticsQueries {
    static final int BUCKETS = 10;

    private final NamedParameterJdbcTemplate jdbc;

    public ExamSummary summary(UUID examId) {
        var exam = new MapSqlParameterSource("exam", examId);
        long[] histogram = new long[BUCKETS];
        jdbc.query("""
                SELECT LEAST(width_bucket(total_score * 100 / max_score, 0, 100, :buckets), :buckets) AS bucket, count(*) AS n
                FROM exam_result_projection
                WHERE exam_id = :exam AND status = 'GRADED' AND max_score > 0
                GROUP BY 1
                """, new MapSqlParameterSource("exam", examId).addValue("buckets", BUCKETS),
                rs -> { histogram[rs.getInt("bucket") - 1] = rs.getLong("n"); });
        var buckets = new ArrayList<ScoreBucket>();
        for (int i = 0; i < BUCKETS; i++) buckets.add(new ScoreBucket(i * 100 / BUCKETS, (i + 1) * 100 / BUCKETS, histogram[i]));

        return jdbc.queryForObject("""
                SELECT count(DISTINCT candidate_id) AS candidates,
                       count(*) AS submissions,
                       count(*) FILTER (WHERE status = 'GRADED') AS graded,
                       count(*) FILTER (WHERE status = 'NEEDS_REVIEW') AS needs_review,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       max(max_score) AS max_possible,
                       round(avg(total_score) FILTER (WHERE status = 'GRADED'), 4) AS avg_score,
                       min(total_score) FILTER (WHERE status = 'GRADED') AS min_score,
                       max(total_score) FILTER (WHERE status = 'GRADED') AS max_score,
                       round(avg(total_score * 100 / max_score) FILTER (WHERE status = 'GRADED' AND max_score > 0), 4) AS avg_percent
                FROM exam_result_projection WHERE exam_id = :exam
                """, exam, (rs, i) -> new ExamSummary(examId,
                rs.getLong("candidates"), rs.getLong("submissions"), rs.getLong("graded"),
                rs.getLong("needs_review"), rs.getLong("pending"), rs.getBigDecimal("max_possible"),
                rs.getBigDecimal("avg_score"), rs.getBigDecimal("min_score"), rs.getBigDecimal("max_score"),
                rs.getBigDecimal("avg_percent"), buckets));
    }

    public List<QuestionStats> questions(UUID examId) {
        return jdbc.query("""
                SELECT question_id, question_version, min(question_type) AS question_type, min(position) AS position, max(point_value) AS point_value,
                       count(*) AS attempts,
                       count(*) FILTER (WHERE outcome <> 'UNANSWERED') AS answered,
                       count(*) FILTER (WHERE outcome = 'CORRECT') AS correct,
                       count(*) FILTER (WHERE outcome = 'PARTIAL') AS partial,
                       count(*) FILTER (WHERE outcome = 'INCORRECT') AS incorrect,
                       count(*) FILTER (WHERE outcome = 'UNANSWERED') AS unanswered,
                       count(*) FILTER (WHERE outcome = 'PENDING_REVIEW') AS pending_review,
                       round(count(*) FILTER (WHERE outcome = 'CORRECT')::numeric
                             / NULLIF(count(*) FILTER (WHERE outcome <> 'PENDING_REVIEW'), 0), 4) AS correct_rate,
                       round(avg(awarded_points), 4) AS avg_awarded
                FROM question_outcome_projection WHERE exam_id = :exam
                GROUP BY question_id, question_version
                ORDER BY position, question_id, question_version
                """, new MapSqlParameterSource("exam", examId), (rs, i) -> new QuestionStats(
                rs.getObject("question_id", UUID.class), rs.getLong("question_version"), rs.getString("question_type"),
                rs.getInt("position"), rs.getBigDecimal("point_value"), rs.getLong("attempts"), rs.getLong("answered"),
                rs.getLong("correct"), rs.getLong("partial"), rs.getLong("incorrect"), rs.getLong("unanswered"),
                rs.getLong("pending_review"), rs.getBigDecimal("correct_rate"), rs.getBigDecimal("avg_awarded")));
    }

    public PageResponse<CandidateResult> results(UUID examId, String status, int page, int size) {
        var params = new MapSqlParameterSource("exam", examId).addValue("status", status)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String filter = "exam_id = :exam AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)";
        long total = jdbc.queryForObject("SELECT count(*) FROM exam_result_projection WHERE " + filter, params, Long.class);
        var rows = jdbc.query("""
                SELECT session_id, result_id, candidate_id, attempt_number, status, total_score, max_score, submitted_at, graded_at
                FROM exam_result_projection WHERE %s
                ORDER BY submitted_at DESC NULLS LAST, session_id
                LIMIT :limit OFFSET :offset
                """.formatted(filter), params, (rs, i) -> new CandidateResult(
                rs.getObject("session_id", UUID.class), rs.getObject("result_id", UUID.class),
                rs.getObject("candidate_id", UUID.class), (Integer) rs.getObject("attempt_number"), rs.getString("status"),
                "GRADED".equals(rs.getString("status")) ? rs.getBigDecimal("total_score") : null,
                rs.getBigDecimal("max_score"), instant(rs.getTimestamp("submitted_at")), instant(rs.getTimestamp("graded_at"))));
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
