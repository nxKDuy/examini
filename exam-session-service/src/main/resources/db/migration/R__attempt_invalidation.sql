-- Repeatable and idempotent: works whether or not the Browser Integrity V3 migration exists on this branch.
ALTER TABLE exam_sessions ADD COLUMN IF NOT EXISTS invalidated_at TIMESTAMPTZ;
ALTER TABLE exam_sessions ADD COLUMN IF NOT EXISTS invalidated_by UUID;
ALTER TABLE exam_sessions ADD COLUMN IF NOT EXISTS invalidation_reason TEXT;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_session_invalidation' AND conrelid='exam_sessions'::regclass) THEN
    ALTER TABLE exam_sessions ADD CONSTRAINT ck_session_invalidation CHECK(
      (invalidated_at IS NULL AND invalidated_by IS NULL AND invalidation_reason IS NULL)
      OR (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL AND length(btrim(invalidation_reason))>0));
  END IF;
END $$;
CREATE TABLE IF NOT EXISTS attempt_events(
  event_id UUID PRIMARY KEY,
  event_type VARCHAR(64) NOT NULL,
  session_id UUID NOT NULL,
  payload TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  grading_delivered BOOLEAN NOT NULL DEFAULT false,
  public_delivered BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_attempt_events_pending ON attempt_events(occurred_at) WHERE NOT grading_delivered OR NOT public_delivered;
CREATE INDEX IF NOT EXISTS idx_sessions_exam_user ON exam_sessions(exam_id,user_id);
