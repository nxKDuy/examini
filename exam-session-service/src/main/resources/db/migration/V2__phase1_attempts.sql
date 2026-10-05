ALTER TABLE exam_sessions ADD COLUMN expires_at TIMESTAMPTZ,
 ADD COLUMN attempt_number INT, ADD COLUMN questions JSONB NOT NULL DEFAULT '[]',
 ADD COLUMN exam_title VARCHAR(255) NOT NULL DEFAULT 'Legacy exam';
-- No trustworthy duration/snapshot exists for legacy active attempts.
-- Refuse upgrade until they finish under the old runtime; never terminate them silently.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM exam_sessions WHERE status='IN_PROGRESS') THEN
   RAISE EXCEPTION 'Drain legacy IN_PROGRESS sessions before Phase 1 migration; do not cancel active students';
 END IF;
END $$;
UPDATE exam_sessions SET expires_at=COALESCE(end_time,start_time) AT TIME ZONE 'UTC';
WITH ranked AS(SELECT id,row_number() OVER(PARTITION BY exam_id,user_id ORDER BY start_time,id) AS n FROM exam_sessions)
UPDATE exam_sessions s SET attempt_number=r.n FROM ranked r WHERE s.id=r.id;
ALTER TABLE exam_sessions ALTER COLUMN expires_at SET NOT NULL, ALTER COLUMN attempt_number SET NOT NULL;
CREATE UNIQUE INDEX unique_attempt ON exam_sessions(exam_id,user_id,attempt_number);
CREATE UNIQUE INDEX unique_active_attempt ON exam_sessions(exam_id,user_id) WHERE status='IN_PROGRESS';
-- Preserve most recent answer before adding uniqueness.
DELETE FROM session_answers a USING session_answers b WHERE a.session_id=b.session_id AND a.question_id=b.question_id AND (COALESCE(a.last_updated_at,'1970-01-01'),a.id)<(COALESCE(b.last_updated_at,'1970-01-01'),b.id);
ALTER TABLE session_answers ADD CONSTRAINT unique_answer UNIQUE(session_id,question_id);
CREATE TABLE submission_events(session_id UUID PRIMARY KEY REFERENCES exam_sessions(id),delivered BOOLEAN NOT NULL DEFAULT false);
