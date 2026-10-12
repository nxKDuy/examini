-- V3: exam.attempt.started and grading.result.published v1 (contracts/events.md, MAIN 9952bbf).
-- Derived and replayable; Session and Grading stay authoritative.

-- One row per started session (the producer emits once per successful Start).
CREATE TABLE attempt_started (
    session_id     UUID PRIMARY KEY,
    exam_id        UUID        NOT NULL,
    candidate_id   UUID        NOT NULL,
    attempt_number INT         NOT NULL CHECK (attempt_number > 0),
    started_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ,
    last_event_id  UUID        NOT NULL,
    projected_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempt_started_exam ON attempt_started(exam_id);

-- Latest released snapshot per session (highest resultRevision wins). Released score only:
-- never the current/draft Grading state, which lives in exam_result_projection.
CREATE TABLE published_result_projection (
    session_id      UUID PRIMARY KEY,
    exam_id         UUID           NOT NULL,
    candidate_id    UUID           NOT NULL,
    result_id       UUID           NOT NULL,
    publication_id  UUID           NOT NULL,
    result_revision BIGINT         NOT NULL CHECK (result_revision >= 0),
    attempt_number  INT            NOT NULL CHECK (attempt_number > 0),
    regraded        BOOLEAN        NOT NULL,
    total_score     NUMERIC        NOT NULL CHECK (total_score >= 0),
    max_score       NUMERIC        NOT NULL CHECK (max_score >= 0),
    graded_at       TIMESTAMPTZ,
    released_at     TIMESTAMPTZ    NOT NULL,
    invalidated     BOOLEAN        NOT NULL,
    last_event_id   UUID           NOT NULL,
    projected_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_published_result_exam ON published_result_projection(exam_id, candidate_id, released_at);
