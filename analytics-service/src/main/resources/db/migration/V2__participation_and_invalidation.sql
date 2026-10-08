-- V2: participant counts and invalidated attempts, projected from examini.events.
-- Exam/Session stay authoritative; these rows are derived and replayable.

-- Latest participant count snapshot per exam (highest recipientRevision wins).
CREATE TABLE exam_participation_projection (
    exam_id            UUID PRIMARY KEY,
    registered_count   INT         NOT NULL CHECK (registered_count >= 0),
    eligible_count     INT         NOT NULL CHECK (eligible_count >= 0),
    revoked_count      INT         NOT NULL CHECK (revoked_count >= 0),
    recipient_revision BIGINT      NOT NULL CHECK (recipient_revision >= 0),
    last_event_id      UUID        NOT NULL,
    projected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (registered_count = eligible_count + revoked_count)
);

-- Trace of invalidated attempts. Independent of exam_result_projection on purpose:
-- invalidation may arrive before or after the result, or have no result at all (CANCELLED).
-- Aggregates exclude a result when a row exists here; the reason/actor are not stored.
CREATE TABLE attempt_invalidation (
    session_id     UUID PRIMARY KEY,
    exam_id        UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    attempt_number INT         NOT NULL CHECK (attempt_number > 0),
    session_status VARCHAR(20) NOT NULL,
    invalidated_at TIMESTAMPTZ NOT NULL,
    last_event_id  UUID        NOT NULL,
    projected_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempt_invalidation_exam ON attempt_invalidation(exam_id);

-- eventId deduplication shared by the examini.events consumers; written in the projection transaction.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
