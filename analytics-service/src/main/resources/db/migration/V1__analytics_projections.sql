-- V1: Analytics-owned read models projected from Grading result events.
-- Grading stays the source of truth; these rows are derived and can be rebuilt by replay.

CREATE TABLE exam_result_projection (
    session_id      UUID PRIMARY KEY,
    result_id       UUID          NOT NULL UNIQUE,
    exam_id         UUID          NOT NULL,
    candidate_id    UUID          NOT NULL,
    attempt_number  INT           CHECK (attempt_number IS NULL OR attempt_number > 0),
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'NEEDS_REVIEW', 'GRADED')),
    total_score     NUMERIC(12,4) CHECK (total_score IS NULL OR total_score >= 0),
    max_score       NUMERIC(12,4) NOT NULL CHECK (max_score >= 0),
    submitted_at    TIMESTAMPTZ,
    graded_at       TIMESTAMPTZ,
    -- Monotonic per-result revision from Grading: idempotency and ordering guard.
    result_revision BIGINT        NOT NULL CHECK (result_revision > 0),
    last_event_id   UUID          NOT NULL,
    projected_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (status <> 'GRADED' OR total_score IS NOT NULL),
    CHECK (total_score IS NULL OR total_score <= max_score)
);

CREATE INDEX idx_result_projection_exam_status ON exam_result_projection(exam_id, status);
CREATE INDEX idx_result_projection_exam_submitted ON exam_result_projection(exam_id, submitted_at DESC, session_id);

-- One row per frozen Exam Question snapshot item inside one graded attempt.
CREATE TABLE question_outcome_projection (
    session_id       UUID          NOT NULL REFERENCES exam_result_projection(session_id) ON DELETE CASCADE,
    question_id      UUID          NOT NULL,
    question_version BIGINT        NOT NULL CHECK (question_version > 0),
    exam_id          UUID          NOT NULL,
    question_type    VARCHAR(30)   NOT NULL,
    position         INT           NOT NULL CHECK (position >= 0),
    point_value      NUMERIC(12,4) NOT NULL CHECK (point_value > 0),
    awarded_points   NUMERIC(12,4) CHECK (awarded_points IS NULL OR (awarded_points >= 0 AND awarded_points <= point_value)),
    outcome          VARCHAR(20)   NOT NULL CHECK (outcome IN ('CORRECT', 'PARTIAL', 'INCORRECT', 'UNANSWERED', 'PENDING_REVIEW')),
    PRIMARY KEY (session_id, question_id),
    CHECK ((outcome = 'PENDING_REVIEW') = (awarded_points IS NULL))
);

CREATE INDEX idx_outcome_exam_question ON question_outcome_projection(exam_id, question_id, question_version);
