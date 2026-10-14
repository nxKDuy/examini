-- Repeatable and idempotent; runs after R__exam_scheduling creates exam_event_outbox.
ALTER TABLE exam_event_outbox ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE exam_event_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ;
ALTER TABLE exam_event_outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(500);
ALTER TABLE exam_event_outbox ADD COLUMN IF NOT EXISTS parked BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_exam_event_outbox_due ON exam_event_outbox(next_attempt_at,occurred_at) WHERE NOT delivered AND NOT parked;
