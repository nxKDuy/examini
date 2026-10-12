package com.datn.analytics.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

import static com.datn.analytics.ingest.EventEnvelope.*;

/** Applies exam.participant.changed / exam.attempt.invalidated / exam.attempt.started idempotently. */
@Service
@RequiredArgsConstructor
public class ParticipationProjector {
    private static final Set<String> CAUSES = Set.of("ASSIGNED", "REINSTATED", "JOINED", "REVOKED", "ATTEMPT_GRANTED");
    private static final Set<String> SESSION_STATUSES = Set.of("SUBMITTED", "AUTO_SUBMITTED", "CANCELLED");

    private final NamedParameterJdbcTemplate jdbc;

    /** @return true when the projection changed; false for a duplicate eventId, stale revision or no-op cause. */
    @Transactional
    public boolean apply(EventEnvelope e) {
        // Validate first so a malformed event never consumes its eventId.
        return switch (e.eventType()) {
            case PARTICIPANT_CHANGED -> participantChanged(e, validateParticipant(e.payload()));
            case ATTEMPT_INVALIDATED -> attemptInvalidated(e, validateInvalidation(e.payload()));
            case ATTEMPT_STARTED -> attemptStarted(e, validateStarted(e.payload()));
            default -> throw new ResultProjector.InvalidEventException("Unsupported eventType " + e.eventType());
        };
    }

    private boolean firstDelivery(EventEnvelope e) {
        return jdbc.update("INSERT INTO processed_event(event_id, event_type) VALUES (:id, :type) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("id", e.eventId()).addValue("type", e.eventType())) == 1;
    }

    record Participant(UUID examId, String cause, long revision, int registered, int eligible, int revoked) {}

    private Participant validateParticipant(JsonNode p) {
        UUID exam = uuid(p, "examId");
        text(p, "examTitle");
        uuid(p, "userId");
        String cause = text(p, "cause");
        if (!CAUSES.contains(cause)) throw new ResultProjector.InvalidEventException("Unknown cause " + cause);
        long revision = longInteger(p, "recipientRevision");
        int registered = integer(p, "registeredCount"), eligible = integer(p, "eligibleCount"), revoked = integer(p, "revokedCount");
        if (revision < 0 || registered < 0 || eligible < 0 || revoked < 0 || registered != eligible + revoked)
            throw new ResultProjector.InvalidEventException("Inconsistent participant counts");
        return new Participant(exam, cause, revision, registered, eligible, revoked);
    }

    private boolean participantChanged(EventEnvelope e, Participant p) {
        if (!firstDelivery(e)) return false;
        // Grants never change counts; they are recorded for dedup only.
        if ("ATTEMPT_GRANTED".equals(p.cause())) return false;
        return jdbc.update("""
                INSERT INTO exam_participation_projection(exam_id, registered_count, eligible_count, revoked_count,
                    recipient_revision, last_event_id)
                VALUES (:exam, :registered, :eligible, :revoked, :revision, :event)
                ON CONFLICT (exam_id) DO UPDATE SET registered_count = EXCLUDED.registered_count,
                    eligible_count = EXCLUDED.eligible_count, revoked_count = EXCLUDED.revoked_count,
                    recipient_revision = EXCLUDED.recipient_revision, last_event_id = EXCLUDED.last_event_id,
                    projected_at = now()
                WHERE exam_participation_projection.recipient_revision < EXCLUDED.recipient_revision
                """, new MapSqlParameterSource("exam", p.examId()).addValue("registered", p.registered())
                .addValue("eligible", p.eligible()).addValue("revoked", p.revoked())
                .addValue("revision", p.revision()).addValue("event", e.eventId())) == 1;
    }

    record Invalidation(UUID sessionId, UUID examId, UUID userId, int attempt, String status, java.time.Instant at) {}

    private Invalidation validateInvalidation(JsonNode p) {
        UUID session = uuid(p, "sessionId"), exam = uuid(p, "examId"), user = uuid(p, "userId");
        int attempt = integer(p, "attemptNumber");
        if (attempt < 1) throw new ResultProjector.InvalidEventException("attemptNumber must be positive");
        String status = text(p, "status");
        if (!SESSION_STATUSES.contains(status)) throw new ResultProjector.InvalidEventException("Unknown session status " + status);
        present(p, "reason");
        present(p, "actor");
        return new Invalidation(session, exam, user, attempt, status, instant(p, "invalidatedAt"));
    }

    private boolean attemptInvalidated(EventEnvelope e, Invalidation v) {
        if (!firstDelivery(e)) return false;
        int inserted = jdbc.update("""
                INSERT INTO attempt_invalidation(session_id, exam_id, user_id, attempt_number, session_status, invalidated_at, last_event_id)
                VALUES (:session, :exam, :user, :attempt, :status, :at, :event)
                ON CONFLICT (session_id) DO NOTHING
                """, new MapSqlParameterSource("session", v.sessionId()).addValue("exam", v.examId())
                .addValue("user", v.userId()).addValue("attempt", v.attempt()).addValue("status", v.status())
                .addValue("at", Timestamp.from(v.at())).addValue("event", e.eventId()));
        if (inserted == 1) return true;
        // Same session invalidated again (new eventId): idempotent unless the identity differs.
        boolean same = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT exam_id = :exam AND user_id = :user AND attempt_number = :attempt
                FROM attempt_invalidation WHERE session_id = :session
                """, new MapSqlParameterSource("session", v.sessionId()).addValue("exam", v.examId())
                .addValue("user", v.userId()).addValue("attempt", v.attempt()), Boolean.class));
        if (!same) throw new ResultProjector.InvalidEventException("Identity conflict for invalidated session " + v.sessionId());
        return false;
    }

    record Started(UUID sessionId, UUID examId, UUID candidateId, int attempt, java.time.Instant startedAt, java.time.Instant expiresAt) {}

    private Started validateStarted(JsonNode p) {
        UUID session = uuid(p, "sessionId"), exam = uuid(p, "examId"), candidate = uuid(p, "candidateId");
        int attempt = integer(p, "attemptNumber");
        if (attempt < 1) throw new ResultProjector.InvalidEventException("attemptNumber must be positive");
        JsonNode expires = p.get("expiresAt");
        return new Started(session, exam, candidate, attempt, instant(p, "startedAt"),
                expires == null || expires.isNull() ? null : instant(p, "expiresAt"));
    }

    /** One row per session; a replay (same or new eventId) of the same start is a no-op. */
    private boolean attemptStarted(EventEnvelope e, Started v) {
        if (!firstDelivery(e)) return false;
        var params = new MapSqlParameterSource("session", v.sessionId()).addValue("exam", v.examId())
                .addValue("candidate", v.candidateId()).addValue("attempt", v.attempt())
                .addValue("started", Timestamp.from(v.startedAt()))
                .addValue("expires", v.expiresAt() == null ? null : Timestamp.from(v.expiresAt()))
                .addValue("event", e.eventId());
        if (jdbc.update("""
                INSERT INTO attempt_started(session_id, exam_id, candidate_id, attempt_number, started_at, expires_at, last_event_id)
                VALUES (:session, :exam, :candidate, :attempt, :started, :expires, :event)
                ON CONFLICT (session_id) DO NOTHING
                """, params) == 1) return true;
        boolean same = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT exam_id = :exam AND candidate_id = :candidate AND attempt_number = :attempt
                FROM attempt_started WHERE session_id = :session
                """, params, Boolean.class));
        if (!same) throw new ResultProjector.InvalidEventException("Identity conflict for started session " + v.sessionId());
        return false;
    }
}
