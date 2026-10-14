-- Relay retries back off exponentially and park after a bounded number of attempts.
ALTER TABLE grading_event_outbox ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE grading_event_outbox ADD COLUMN last_error VARCHAR(500);
ALTER TABLE grading_event_outbox ADD COLUMN parked BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX idx_grading_outbox_due ON grading_event_outbox(next_attempt_at,occurred_at) WHERE NOT delivered AND NOT parked;
