-- Repeatable bridge: session Phase 2 V3 may be before or after this migration.
-- A checksum-triggered rerun validates existing columns and indexes in place.
DO $$
DECLARE
  actual_type TEXT;
  idx_def TEXT;
BEGIN
  SELECT data_type INTO actual_type FROM information_schema.columns
    WHERE table_schema=current_schema() AND table_name='exam_sessions' AND column_name='schedule_revision';
  IF actual_type IS NULL THEN
    ALTER TABLE exam_sessions ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 0 CHECK(schedule_revision>=0);
  ELSIF actual_type <> 'bigint' THEN
    RAISE EXCEPTION 'exam_sessions.schedule_revision is %, expected bigint',actual_type;
  END IF;

  SELECT data_type INTO actual_type FROM information_schema.columns
    WHERE table_schema=current_schema() AND table_name='exam_sessions' AND column_name='start_admission_id';
  IF actual_type IS NULL THEN
    ALTER TABLE exam_sessions ADD COLUMN start_admission_id UUID;
  ELSIF actual_type <> 'uuid' THEN
    RAISE EXCEPTION 'exam_sessions.start_admission_id is %, expected uuid',actual_type;
  END IF;

  CREATE UNIQUE INDEX IF NOT EXISTS idx_session_admission_unique ON exam_sessions(start_admission_id) WHERE start_admission_id IS NOT NULL;
  SELECT indexdef INTO idx_def FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_session_admission_unique';
  IF idx_def IS NULL OR idx_def NOT LIKE '%(start_admission_id)%' OR idx_def NOT LIKE '%IS NOT NULL%' THEN
    RAISE EXCEPTION 'idx_session_admission_unique has unexpected definition';
  END IF;
END $$;
