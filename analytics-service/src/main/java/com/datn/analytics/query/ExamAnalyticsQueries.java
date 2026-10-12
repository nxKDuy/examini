package com.datn.analytics.query;

import com.datn.analytics.query.AnalyticsDto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
                FROM exam_result_projection r
                WHERE exam_id = :exam AND status = 'GRADED' AND max_score > 0 AND NOT EXISTS (SELECT 1 FROM attempt_invalidation i WHERE i.session_id = r.session_id)
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
                FROM exam_result_projection r WHERE exam_id = :exam AND NOT EXISTS (SELECT 1 FROM attempt_invalidation i WHERE i.session_id = r.session_id)
                """, exam, (rs, i) -> {
            Integer registered = null, eligible = null;
            var participation = jdbc.query("SELECT registered_count, eligible_count FROM exam_participation_projection WHERE exam_id = :exam",
                    exam, (p, n) -> new Integer[]{p.getInt(1), p.getInt(2)});
            if (!participation.isEmpty()) { registered = participation.get(0)[0]; eligible = participation.get(0)[1]; }
            long candidates = rs.getLong("candidates");
            // Null (never 0) while no participant projection exists or nobody is eligible.
            BigDecimal rate = eligible == null || eligible == 0 ? null
                    : BigDecimal.valueOf(candidates).divide(BigDecimal.valueOf(eligible), 4, java.math.RoundingMode.HALF_UP);
            long submissions = rs.getLong("submissions");
            long graded = rs.getLong("graded");
            BigDecimal gradingCompletionRate = submissions == 0 ? null
                    : BigDecimal.valueOf(graded).divide(BigDecimal.valueOf(submissions), 4, java.math.RoundingMode.HALF_UP);
            long invalidated = jdbc.queryForObject("SELECT count(*) FROM attempt_invalidation WHERE exam_id = :exam", exam, Long.class);
            return new ExamSummary(examId, candidates, submissions, graded,
                    rs.getLong("needs_review"), rs.getLong("pending"), rs.getBigDecimal("max_possible"),
                    rs.getBigDecimal("avg_score"), rs.getBigDecimal("min_score"), rs.getBigDecimal("max_score"),
                    rs.getBigDecimal("avg_percent"), buckets, registered, eligible, invalidated, rate, gradingCompletionRate);
        });
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
                FROM question_outcome_projection o WHERE exam_id = :exam
                  AND NOT EXISTS (SELECT 1 FROM attempt_invalidation i WHERE i.session_id = o.session_id)
                GROUP BY question_id, question_version
                ORDER BY position, question_id, question_version
                """, new MapSqlParameterSource("exam", examId), (rs, i) -> new QuestionStats(
                rs.getObject("question_id", UUID.class), rs.getLong("question_version"), rs.getString("question_type"),
                rs.getInt("position"), rs.getBigDecimal("point_value"), rs.getLong("attempts"), rs.getLong("answered"),
                rs.getLong("correct"), rs.getLong("partial"), rs.getLong("incorrect"), rs.getLong("unanswered"),
                rs.getLong("pending_review"), rs.getBigDecimal("correct_rate"), rs.getBigDecimal("avg_awarded")));
    }

    public PageResponse<CandidateResult> results(UUID examId, String status, int page, int size) {
        return results(examId, status, null, null, page, size);
    }

    public PageResponse<CandidateResult> results(UUID examId, String status, UUID candidateId, Boolean invalidated, int page, int size) {
        var params = new MapSqlParameterSource("exam", examId).addValue("status", status)
                .addValue("candidate", candidateId).addValue("invalidated", invalidated)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String filter = """
                r.exam_id = :exam
                AND (CAST(:status AS VARCHAR) IS NULL OR r.status = :status)
                AND (CAST(:candidate AS UUID) IS NULL OR r.candidate_id = :candidate)
                AND (CAST(:invalidated AS BOOLEAN) IS NULL OR
                     EXISTS (SELECT 1 FROM attempt_invalidation i WHERE i.session_id = r.session_id) = :invalidated)
                """;
        long total = jdbc.queryForObject("SELECT count(*) FROM exam_result_projection r WHERE " + filter, params, Long.class);
        var rows = jdbc.query("""
                SELECT r.session_id, r.result_id, r.candidate_id, r.attempt_number, r.status, r.total_score, r.max_score, r.submitted_at, r.graded_at,
                       EXISTS (SELECT 1 FROM attempt_invalidation i WHERE i.session_id = r.session_id) AS invalidated
                FROM exam_result_projection r WHERE %s
                ORDER BY r.submitted_at DESC NULLS LAST, r.session_id
                LIMIT :limit OFFSET :offset
                """.formatted(filter), params, (rs, i) -> new CandidateResult(
                rs.getObject("session_id", UUID.class), rs.getObject("result_id", UUID.class),
                rs.getObject("candidate_id", UUID.class), (Integer) rs.getObject("attempt_number"), rs.getString("status"),
                "GRADED".equals(rs.getString("status")) ? rs.getBigDecimal("total_score") : null,
                rs.getBigDecimal("max_score"), instant(rs.getTimestamp("submitted_at")), instant(rs.getTimestamp("graded_at")),
                rs.getBoolean("invalidated")));
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    public PageResponse<StudentSummary> students(UUID examId, int page, int size) {
        var params = new MapSqlParameterSource("exam", examId)
                .addValue("limit", size).addValue("offset", (long) page * size);
        String valid = "r.exam_id = :exam AND NOT EXISTS (SELECT 1 FROM attempt_invalidation i WHERE i.session_id = r.session_id)";
        long total = jdbc.queryForObject("SELECT count(DISTINCT r.candidate_id) FROM exam_result_projection r WHERE " + valid,
                params, Long.class);
        var rows = jdbc.query("""
                WITH ranked AS (
                    SELECT r.*, row_number() OVER (PARTITION BY candidate_id ORDER BY submitted_at DESC NULLS LAST, session_id DESC) AS rank
                    FROM exam_result_projection r WHERE %s
                )
                SELECT candidate_id, count(*) AS submitted,
                       count(*) FILTER (WHERE status = 'GRADED') AS graded,
                       count(*) FILTER (WHERE status = 'NEEDS_REVIEW') AS needs_review,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       (array_agg(session_id) FILTER (WHERE rank = 1))[1] AS latest_session,
                       max(status) FILTER (WHERE rank = 1) AS latest_status,
                       max(submitted_at) FILTER (WHERE rank = 1) AS latest_submitted
                FROM ranked GROUP BY candidate_id
                ORDER BY latest_submitted DESC NULLS LAST, candidate_id
                LIMIT :limit OFFSET :offset
                """.formatted(valid), params, (rs, i) -> new StudentSummary(
                rs.getObject("candidate_id", UUID.class), rs.getLong("submitted"), rs.getLong("graded"),
                rs.getLong("needs_review"), rs.getLong("pending"), rs.getObject("latest_session", UUID.class),
                rs.getString("latest_status"), instant(rs.getTimestamp("latest_submitted"))));
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
