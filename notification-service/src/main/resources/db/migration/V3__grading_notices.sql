-- V3: manual-grading notices. Additive: V1/V2 rows and tables are untouched.

-- Teacher review fan-out reuses the durable scheduling job runner (SKIP LOCKED, lease, backoff, expiry).
ALTER TABLE scheduling_jobs DROP CONSTRAINT scheduling_jobs_kind_check;
ALTER TABLE scheduling_jobs ADD CONSTRAINT scheduling_jobs_kind_check
    CHECK (kind IN ('SCHEDULE', 'LIFECYCLE', 'REMINDER', 'GRADING_REVIEW'));

-- Latest revision handled per session and stream. The three streams are independent: a regrade that overtakes the first
-- release must not make the first release look stale. One Result per session (Grading keeps session_id unique).
CREATE TABLE grading_notice_state (
    session_id        UUID PRIMARY KEY,
    review_revision   BIGINT      NOT NULL DEFAULT 0,
    released_revision BIGINT      NOT NULL DEFAULT 0,
    regraded_revision BIGINT      NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
