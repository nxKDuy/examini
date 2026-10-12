package com.datn.analytics.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static com.datn.analytics.ingest.EventEnvelope.*;

/**
 * Applies grading.result.published v1: keeps the highest resultRevision per session, so duplicates,
 * replays/backfills and out-of-order releases converge. Question outcomes are validated, not stored.
 */
@Service
@RequiredArgsConstructor
public class PublishedResultProjector {
    private final NamedParameterJdbcTemplate jdbc;

    record Published(UUID publicationId, UUID resultId, long revision, UUID sessionId, UUID examId, UUID candidateId,
                     int attempt, boolean regraded, BigDecimal total, BigDecimal max, Instant gradedAt, Instant releasedAt,
                     boolean invalidated) {}

    /** @return true when the projection changed; false for a duplicate eventId or an older revision. */
    @Transactional
    public boolean apply(EventEnvelope e) {
        if (!RESULT_PUBLISHED.equals(e.eventType())) throw new ResultProjector.InvalidEventException("Unsupported eventType " + e.eventType());
        var p = validate(e.payload());
        if (jdbc.update("INSERT INTO processed_event(event_id, event_type) VALUES (:id, :type) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("id", e.eventId()).addValue("type", e.eventType())) == 0) return false;
        var params = new MapSqlParameterSource("session", p.sessionId()).addValue("exam", p.examId())
                .addValue("candidate", p.candidateId()).addValue("result", p.resultId())
                .addValue("publication", p.publicationId()).addValue("revision", p.revision())
                .addValue("attempt", p.attempt()).addValue("regraded", p.regraded())
                .addValue("total", p.total()).addValue("max", p.max())
                .addValue("graded", p.gradedAt() == null ? null : Timestamp.from(p.gradedAt()))
                .addValue("released", Timestamp.from(p.releasedAt())).addValue("invalidated", p.invalidated())
                .addValue("event", e.eventId());
        // The identity guard sits in the conflict WHERE so a conflicting session never overwrites the row.
        if (jdbc.update("""
                INSERT INTO published_result_projection(session_id, exam_id, candidate_id, result_id, publication_id,
                    result_revision, attempt_number, regraded, total_score, max_score, graded_at, released_at, invalidated, last_event_id)
                VALUES (:session, :exam, :candidate, :result, :publication, :revision, :attempt, :regraded, :total, :max,
                    :graded, :released, :invalidated, :event)
                ON CONFLICT (session_id) DO UPDATE SET publication_id = EXCLUDED.publication_id,
                    result_revision = EXCLUDED.result_revision, attempt_number = EXCLUDED.attempt_number,
                    regraded = EXCLUDED.regraded, total_score = EXCLUDED.total_score, max_score = EXCLUDED.max_score,
                    graded_at = EXCLUDED.graded_at, released_at = EXCLUDED.released_at, invalidated = EXCLUDED.invalidated,
                    last_event_id = EXCLUDED.last_event_id, projected_at = now()
                WHERE published_result_projection.result_revision < EXCLUDED.result_revision
                  AND published_result_projection.exam_id = EXCLUDED.exam_id
                  AND published_result_projection.candidate_id = EXCLUDED.candidate_id
                  AND published_result_projection.result_id = EXCLUDED.result_id
                """, params) == 1) return true;
        boolean same = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT exam_id = :exam AND candidate_id = :candidate AND result_id = :result
                FROM published_result_projection WHERE session_id = :session
                """, params, Boolean.class));
        if (!same) throw new ResultProjector.InvalidEventException("Identity conflict for published session " + p.sessionId());
        return false;
    }

    private Published validate(JsonNode p) {
        UUID publication = uuid(p, "publicationId"), result = uuid(p, "resultId"), session = uuid(p, "sessionId");
        UUID exam = uuid(p, "examId"), candidate = uuid(p, "candidateId");
        long revision = longInteger(p, "resultRevision");
        int attempt = integer(p, "attemptNumber");
        if (revision < 0 || attempt < 1) throw new ResultProjector.InvalidEventException("Invalid resultRevision/attemptNumber");
        BigDecimal total = decimal(p, "totalScore"), max = decimal(p, "maxScore");
        if (total.signum() < 0 || max.signum() < 0) throw new ResultProjector.InvalidEventException("Scores must be non-negative");
        text(p, "gradingMode");
        JsonNode graded = p.get("gradedAt"), questions = p.get("questions");
        if (questions == null || !questions.isArray()) throw new ResultProjector.InvalidEventException("questions must be an array");
        return new Published(publication, result, revision, session, exam, candidate, attempt, bool(p, "regraded"), total, max,
                graded == null || graded.isNull() ? null : instant(p, "gradedAt"), instant(p, "releasedAt"), bool(p, "invalidated"));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) throw new ResultProjector.InvalidEventException(field + " must be a JSON number");
        return v.decimalValue();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isBoolean()) throw new ResultProjector.InvalidEventException(field + " must be a JSON boolean");
        return v.booleanValue();
    }
}
