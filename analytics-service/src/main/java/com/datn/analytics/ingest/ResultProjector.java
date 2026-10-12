package com.datn.analytics.ingest;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Idempotently applies Grading result state to the Analytics read models. */
@Service
@RequiredArgsConstructor
public class ResultProjector {

    public enum Outcome { CORRECT, PARTIAL, INCORRECT, UNANSWERED, PENDING_REVIEW }

    /** Same key derivation is used by the regression test to gate concurrent deliveries. */
    public static final String SESSION_LOCK_KEY = "hashtextextended(CAST(:session AS text), 0)";

    private static final Set<String> STATUSES = Set.of("PENDING", "NEEDS_REVIEW", "GRADED");

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @return true when the event changed the projection; false for a duplicate or stale revision.
     */
    @Transactional
    public boolean apply(GradingResultEvent e) {
        validate(e);
        // Serialize every delivery for this session BEFORE the identity check. A row lock cannot
        // protect a session row that does not exist yet (concurrent conflicting first deliveries).
        jdbc.queryForList("SELECT pg_advisory_xact_lock(" + SESSION_LOCK_KEY + ")",
                new MapSqlParameterSource("session", e.sessionId().toString()));
        var existing = jdbc.query("""
                SELECT result_id, exam_id, candidate_id FROM exam_result_projection
                WHERE session_id = :session
                """, new MapSqlParameterSource("session", e.sessionId()),
                (rs, i) -> rs.getObject("result_id").equals(e.resultId()) && rs.getObject("exam_id").equals(e.examId())
                        && rs.getObject("candidate_id").equals(e.candidateId()));
        if (existing.contains(false)) throw new InvalidEventException("Identity conflict for session " + e.sessionId());
        int changed = jdbc.update("""
                INSERT INTO exam_result_projection(session_id, result_id, exam_id, candidate_id, attempt_number, status,
                    total_score, max_score, submitted_at, graded_at, result_revision, last_event_id)
                VALUES (:session, :result, :exam, :candidate, :attempt, :status,
                    :total, :max, :submitted, :graded, :revision, :event)
                ON CONFLICT (session_id) DO UPDATE SET
                    status = EXCLUDED.status, total_score = EXCLUDED.total_score, max_score = EXCLUDED.max_score,
                    attempt_number = COALESCE(EXCLUDED.attempt_number, exam_result_projection.attempt_number),
                    submitted_at = EXCLUDED.submitted_at,
                    graded_at = EXCLUDED.graded_at, result_revision = EXCLUDED.result_revision,
                    last_event_id = EXCLUDED.last_event_id, projected_at = now()
                WHERE exam_result_projection.result_revision < EXCLUDED.result_revision
                  AND exam_result_projection.result_id = EXCLUDED.result_id
                  AND exam_result_projection.exam_id = EXCLUDED.exam_id
                  AND exam_result_projection.candidate_id = EXCLUDED.candidate_id
                """, new MapSqlParameterSource()
                .addValue("session", e.sessionId()).addValue("result", e.resultId())
                .addValue("exam", e.examId()).addValue("candidate", e.candidateId())
                .addValue("attempt", knownAttempt(e.attemptNumber())).addValue("status", e.status())
                .addValue("total", e.totalScore()).addValue("max", e.maxScore())
                .addValue("submitted", timestamp(e.submittedAt())).addValue("graded", timestamp(e.gradedAt()))
                .addValue("revision", e.resultRevision()).addValue("event", e.eventId()));
        if (changed == 0) return false;

        jdbc.update("DELETE FROM question_outcome_projection WHERE session_id = :session",
                new MapSqlParameterSource("session", e.sessionId()));
        var rows = e.questions().stream().map(q -> {
            Outcome outcome = outcome(q);
            return new MapSqlParameterSource()
                    .addValue("session", e.sessionId()).addValue("exam", e.examId())
                    .addValue("question", q.questionId()).addValue("version", q.questionVersion())
                    .addValue("type", q.questionType()).addValue("position", q.position())
                    .addValue("points", q.pointValue())
                    .addValue("awarded", outcome == Outcome.PENDING_REVIEW ? null
                            : q.answered() ? q.awardedPoints() : BigDecimal.ZERO)
                    .addValue("outcome", outcome.name());
        }).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO question_outcome_projection(session_id, question_id, question_version, exam_id,
                    question_type, position, point_value, awarded_points, outcome)
                VALUES (:session, :question, :version, :exam, :type, :position, :points, :awarded, :outcome)
                """, rows);
        return true;
    }

    /** Correctness is Grading's awarded score relative to the snapshot points; Analytics never re-grades. */
    static Outcome outcome(GradingResultEvent.QuestionItem q) {
        if (q.needsReview()) return Outcome.PENDING_REVIEW;
        if (!q.answered()) return Outcome.UNANSWERED;
        if (q.awardedPoints().compareTo(q.pointValue()) >= 0) return Outcome.CORRECT;
        if (q.awardedPoints().signum() <= 0) return Outcome.INCORRECT;
        return Outcome.PARTIAL;
    }

    static Integer knownAttempt(Integer attemptNumber) {
        return attemptNumber == null || attemptNumber == 0 ? null : attemptNumber;
    }

    static void validate(GradingResultEvent e) {
        require(e != null, "Missing event");
        require(e.schemaVersion() == GradingResultEvent.SCHEMA_VERSION, "Unsupported schemaVersion " + e.schemaVersion());
        require(e.occurredAt() != null, "occurredAt required");
        require(e.eventId() != null && e.resultId() != null && e.sessionId() != null && e.examId() != null
                && e.candidateId() != null, "Missing identity");
        require(e.resultRevision() > 0, "resultRevision must be positive");
        require(e.status() != null && STATUSES.contains(e.status()), "Unknown status " + e.status());
        // 0 is the legacy "unknown" sentinel (Grading int default before Session exposed the attempt);
        // it is projected as null instead of dead-lettering the whole result. Negative is still a violation.
        require(e.attemptNumber() == null || e.attemptNumber() >= 0, "attemptNumber must not be negative");
        require(e.maxScore() != null && e.maxScore().signum() >= 0, "maxScore required");
        require(!"GRADED".equals(e.status()) || e.totalScore() != null, "GRADED result requires totalScore");
        require(e.totalScore() == null || (e.totalScore().signum() >= 0 && e.totalScore().compareTo(e.maxScore()) <= 0),
                "totalScore outside [0, maxScore]");
        require(e.questions() != null, "questions required");
        Set<java.util.UUID> seen = new HashSet<>();
        for (var q : e.questions()) {
            require(q != null && q.questionId() != null && seen.add(q.questionId()), "Missing or duplicate questionId");
            require(q.questionVersion() > 0, "questionVersion must be positive");
            require(q.questionType() != null && !q.questionType().isBlank(), "questionType required");
            require(q.position() >= 0, "position must not be negative");
            require(q.pointValue() != null && q.pointValue().signum() > 0, "pointValue must be positive");
            require(q.needsReview() || !q.answered() || q.awardedPoints() != null, "awardedPoints required once graded");
            require(q.awardedPoints() == null || (q.awardedPoints().signum() >= 0
                    && q.awardedPoints().compareTo(q.pointValue()) <= 0), "awardedPoints outside [0, pointValue]");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new InvalidEventException(message);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static class InvalidEventException extends IllegalArgumentException {
        public InvalidEventException(String message) { super(message); }
    }
}
