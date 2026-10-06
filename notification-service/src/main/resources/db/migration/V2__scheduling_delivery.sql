-- V2: Exam Scheduling notifications. Additive only: V1 inbox rows and columns are unchanged.

-- Latest schedule/lifecycle/recipient revisions seen per Exam: three independent sequences (Exam stays the authority).
CREATE TABLE exam_schedule_state (
    exam_id            UUID PRIMARY KEY,
    exam_title         VARCHAR(255) NOT NULL,
    status             VARCHAR(16),
    schedule_revision  BIGINT       NOT NULL DEFAULT 0,
    lifecycle_revision BIGINT       NOT NULL DEFAULT 0,
    recipient_revision BIGINT       NOT NULL DEFAULT 0,
    start_at           TIMESTAMPTZ,
    end_at             TIMESTAMPTZ,
    timezone           VARCHAR(64),
    deadline_rule      VARCHAR(32),
    reminder_offsets   JSONB        NOT NULL DEFAULT '[]',
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Durable fan-out work: one job per schedule/lifecycle event and one per reminder (exam, startAt, offset).
CREATE TABLE scheduling_jobs (
    id              UUID PRIMARY KEY,
    kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('SCHEDULE', 'LIFECYCLE', 'REMINDER')),
    exam_id         UUID         NOT NULL,
    source_id       UUID         NOT NULL,
    revision        BIGINT       NOT NULL,
    start_at        TIMESTAMPTZ,
    offset_minutes  INT,
    payload         JSONB        NOT NULL DEFAULT '{}',
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'CANCELLED', 'EXPIRED', 'FAILED')),
    due_at          TIMESTAMPTZ  NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- CANCELLED rows never block replanning (Reopen, or rescheduling back to the same startAt/offset);
-- DONE/EXPIRED/FAILED rows do, so a sent reminder is never planned again.
CREATE UNIQUE INDEX scheduling_job_once ON scheduling_jobs(kind, source_id) WHERE status <> 'CANCELLED';
CREATE INDEX scheduling_job_due ON scheduling_jobs(next_attempt_at) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX scheduling_job_exam ON scheduling_jobs(exam_id, kind) WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE push_subscriptions (
    id          UUID PRIMARY KEY,
    user_id     UUID          NOT NULL,
    endpoint    VARCHAR(2048) NOT NULL UNIQUE,
    p256dh      VARCHAR(128)  NOT NULL,
    auth_secret VARCHAR(64)   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX push_subscription_user ON push_subscriptions(user_id) WHERE revoked_at IS NULL;

-- Email and push delivery per notification; push is tracked per subscription so partial success is never resent.
-- Contact addresses are not stored: email is resolved from Auth at send time.
CREATE TABLE notification_deliveries (
    id                UUID PRIMARY KEY,
    notification_id   UUID         NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    recipient_user_id UUID         NOT NULL,
    channel           VARCHAR(8)   NOT NULL CHECK (channel IN ('EMAIL', 'PUSH')),
    subscription_id   UUID REFERENCES push_subscriptions(id),
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED', 'EXPIRED')),
    skip_reason       VARCHAR(32),
    attempts          INT          NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ  NOT NULL,
    locked_until      TIMESTAMPTZ,
    provider_status   INT,
    last_error        VARCHAR(300),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ
);
CREATE UNIQUE INDEX delivery_once ON notification_deliveries(notification_id, channel, subscription_id) NULLS NOT DISTINCT;
CREATE INDEX delivery_due ON notification_deliveries(next_attempt_at) WHERE status IN ('PENDING', 'SENDING');
