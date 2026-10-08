-- Repeatable and idempotent: runs on MAIN (V3) and on the branch that also carries Browser Integrity V4.
-- Participants become stateful rows (revocation keeps history) and per-Student attempt grants get their own ledger.
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS added_by UUID;
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ;
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS status_changed_by UUID;
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS revocation_reason TEXT;
ALTER TABLE exam_participants ADD COLUMN IF NOT EXISTS extra_attempts INT NOT NULL DEFAULT 0;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_exam_participant_status' AND conrelid='exam_participants'::regclass) THEN
    ALTER TABLE exam_participants ADD CONSTRAINT ck_exam_participant_status CHECK(status IN ('ACTIVE','REVOKED'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_exam_participant_extra' AND conrelid='exam_participants'::regclass) THEN
    ALTER TABLE exam_participants ADD CONSTRAINT ck_exam_participant_extra CHECK(extra_attempts>=0);
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_exam_participants_roster ON exam_participants(exam_id,status,created_at);
CREATE INDEX IF NOT EXISTS idx_exam_participants_user ON exam_participants(user_id);

CREATE TABLE IF NOT EXISTS exam_attempt_grants(
  id UUID PRIMARY KEY,
  exam_id UUID NOT NULL REFERENCES exams(id),
  user_id UUID NOT NULL,
  quantity INT NOT NULL CHECK(quantity>0),
  reason TEXT NOT NULL CHECK(length(btrim(reason))>0),
  actor_user_id UUID NOT NULL,
  request_id VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_attempt_grant_request UNIQUE(exam_id,user_id,request_id)
);
CREATE INDEX IF NOT EXISTS idx_attempt_grants_participant ON exam_attempt_grants(exam_id,user_id,created_at);
