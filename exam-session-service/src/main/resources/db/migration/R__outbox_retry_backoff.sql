-- Repeatable and idempotent; attempt_events is created by R__attempt_invalidation (runs earlier by name).
ALTER TABLE submission_events ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE submission_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;
ALTER TABLE submission_events ADD COLUMN IF NOT EXISTS last_error VARCHAR(500);
ALTER TABLE submission_events ADD COLUMN IF NOT EXISTS parked BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_submission_events_due ON submission_events(next_attempt_at) WHERE NOT delivered AND NOT parked;
ALTER TABLE attempt_events ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE attempt_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;
ALTER TABLE attempt_events ADD COLUMN IF NOT EXISTS last_error VARCHAR(500);
ALTER TABLE attempt_events ADD COLUMN IF NOT EXISTS parked BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_attempt_events_due ON attempt_events(next_attempt_at,occurred_at) WHERE (NOT grading_delivered OR NOT public_delivered) AND NOT parked;
