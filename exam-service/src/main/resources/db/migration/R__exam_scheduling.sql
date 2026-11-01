-- Scheduling bridge migration. Repeatable so committed MAIN V1-V3 and the
-- unmerged Phase 2 V4 can both precede it without a version collision.
-- A rerun validates structure and never rewrites rows already backfilled.
DO $$
DECLARE
  c RECORD;
  actual_type TEXT;
  idx_def TEXT;
BEGIN
  FOR c IN SELECT * FROM (VALUES
    ('start_at','timestamp with time zone'),
    ('end_at','timestamp with time zone'),
    ('timezone','character varying'),
    ('deadline_rule','character varying'),
    ('auto_open','boolean'),
    ('auto_close','boolean'),
    ('reminder_offsets_minutes','jsonb'),
    ('schedule_revision','bigint'),
    ('lifecycle_revision','bigint'),
    ('recipient_revision','bigint'),
    ('first_student_started_at','timestamp with time zone'),
    ('schedule_legacy_backfilled','boolean')
  ) AS expected(column_name,data_type)
  LOOP
    SELECT data_type INTO actual_type FROM information_schema.columns
      WHERE table_schema=current_schema() AND table_name='exams' AND column_name=c.column_name;
    IF actual_type IS NULL THEN
      CASE c.column_name
        WHEN 'start_at' THEN ALTER TABLE exams ADD COLUMN start_at TIMESTAMPTZ;
        WHEN 'end_at' THEN ALTER TABLE exams ADD COLUMN end_at TIMESTAMPTZ;
        WHEN 'timezone' THEN ALTER TABLE exams ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh';
        WHEN 'deadline_rule' THEN ALTER TABLE exams ADD COLUMN deadline_rule VARCHAR(32) NOT NULL DEFAULT 'FULL_DURATION';
        WHEN 'auto_open' THEN ALTER TABLE exams ADD COLUMN auto_open BOOLEAN NOT NULL DEFAULT false;
        WHEN 'auto_close' THEN ALTER TABLE exams ADD COLUMN auto_close BOOLEAN NOT NULL DEFAULT false;
        WHEN 'reminder_offsets_minutes' THEN ALTER TABLE exams ADD COLUMN reminder_offsets_minutes JSONB NOT NULL DEFAULT '[1440,15]'::jsonb;
        WHEN 'schedule_revision' THEN ALTER TABLE exams ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 0;
        WHEN 'lifecycle_revision' THEN ALTER TABLE exams ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;
        WHEN 'recipient_revision' THEN ALTER TABLE exams ADD COLUMN recipient_revision BIGINT NOT NULL DEFAULT 0;
        WHEN 'first_student_started_at' THEN ALTER TABLE exams ADD COLUMN first_student_started_at TIMESTAMPTZ;
        WHEN 'schedule_legacy_backfilled' THEN ALTER TABLE exams ADD COLUMN schedule_legacy_backfilled BOOLEAN NOT NULL DEFAULT false;
      END CASE;
    ELSIF actual_type <> c.data_type THEN
      RAISE EXCEPTION 'exams.%(type) is %, expected %', c.column_name, actual_type, c.data_type;
    END IF;
  END LOOP;

  UPDATE exams SET
    start_at=CASE WHEN start_time IS NULL THEN NULL ELSE start_time AT TIME ZONE 'UTC' END,
    end_at=CASE WHEN end_time IS NULL THEN NULL ELSE end_time AT TIME ZONE 'UTC' END,
    schedule_revision=CASE WHEN start_time IS NULL AND end_time IS NULL THEN 0 ELSE 1 END,
    schedule_legacy_backfilled=true
  WHERE schedule_legacy_backfilled=false;
  ALTER TABLE exams ALTER COLUMN schedule_legacy_backfilled SET DEFAULT true;

  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_deadline_rule') THEN
    ALTER TABLE exams ADD CONSTRAINT ck_exam_deadline_rule CHECK(deadline_rule IN ('FULL_DURATION','CAPPED_AT_END','REQUIRE_FULL_WINDOW'));
  ELSIF (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_deadline_rule')
        NOT LIKE '%FULL_DURATION%' OR (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_deadline_rule')
        NOT LIKE '%REQUIRE_FULL_WINDOW%' THEN
    RAISE EXCEPTION 'ck_exam_deadline_rule exists with an unexpected definition';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_schedule_revision') THEN
    ALTER TABLE exams ADD CONSTRAINT ck_exam_schedule_revision CHECK(schedule_revision>=0 AND lifecycle_revision>=0 AND recipient_revision>=0);
  ELSIF (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_schedule_revision')
        NOT LIKE '%schedule_revision%lifecycle_revision%recipient_revision%' THEN
    RAISE EXCEPTION 'ck_exam_schedule_revision exists with an unexpected definition';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_reminder_offsets_array') THEN
    ALTER TABLE exams ADD CONSTRAINT ck_exam_reminder_offsets_array CHECK(jsonb_typeof(reminder_offsets_minutes)='array');
  ELSIF (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='exams'::regclass AND conname='ck_exam_reminder_offsets_array')
        NOT LIKE '%jsonb_typeof%' THEN
    RAISE EXCEPTION 'ck_exam_reminder_offsets_array exists with an unexpected definition';
  END IF;

  ALTER TABLE exam_audit ALTER COLUMN actor_user_id DROP NOT NULL;

  CREATE TABLE IF NOT EXISTS exam_followers (
    exam_id UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(exam_id,user_id)
  );
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='exam_followers'
      AND (column_name,data_type) IN (('exam_id','uuid'),('user_id','uuid'),('created_at','timestamp with time zone'))) <> 3 THEN
    RAISE EXCEPTION 'exam_followers has an unexpected schema';
  END IF;
  CREATE INDEX IF NOT EXISTS idx_exam_followers_user ON exam_followers(user_id,exam_id);
  SELECT indexdef INTO idx_def FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_exam_followers_user';
  IF idx_def IS NULL OR idx_def NOT LIKE '%(user_id, exam_id)%' THEN RAISE EXCEPTION 'idx_exam_followers_user has unexpected definition'; END IF;

  CREATE TABLE IF NOT EXISTS exam_start_admissions (
    id UUID PRIMARY KEY,
    exam_id UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    schedule_revision BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK(state IN ('PENDING','CLAIMED','STARTED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    session_id UUID
  );
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='exam_start_admissions'
      AND (column_name,data_type) IN (('id','uuid'),('exam_id','uuid'),('user_id','uuid'),('schedule_revision','bigint'),('state','character varying'),('created_at','timestamp with time zone'),('expires_at','timestamp with time zone'),('started_at','timestamp with time zone'),('deadline_at','timestamp with time zone'),('session_id','uuid'))) <> 10 THEN
    RAISE EXCEPTION 'exam_start_admissions has an unexpected schema';
  END IF;
  CREATE UNIQUE INDEX IF NOT EXISTS idx_exam_start_admission_active_user ON exam_start_admissions(exam_id,user_id) WHERE state IN ('PENDING','CLAIMED');
  CREATE INDEX IF NOT EXISTS idx_exam_start_admission_reconcile ON exam_start_admissions(state,expires_at);
  SELECT indexdef INTO idx_def FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_exam_start_admission_active_user';
  IF idx_def IS NULL OR idx_def NOT LIKE '%(exam_id, user_id)%' OR idx_def NOT LIKE '%PENDING%' OR idx_def NOT LIKE '%CLAIMED%' THEN RAISE EXCEPTION 'active admission index has unexpected definition'; END IF;
  SELECT indexdef INTO idx_def FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_exam_start_admission_reconcile';
  IF idx_def IS NULL OR idx_def NOT LIKE '%(state, expires_at)%' THEN RAISE EXCEPTION 'admission reconciliation index has unexpected definition'; END IF;

  CREATE TABLE IF NOT EXISTS exam_event_outbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL CHECK(jsonb_typeof(payload)='object'),
    occurred_at TIMESTAMPTZ NOT NULL,
    delivered BOOLEAN NOT NULL DEFAULT false
  );
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='exam_event_outbox'
      AND (column_name,data_type) IN (('event_id','uuid'),('event_type','character varying'),('payload','jsonb'),('occurred_at','timestamp with time zone'),('delivered','boolean'))) <> 5 THEN
    RAISE EXCEPTION 'exam_event_outbox has an unexpected schema';
  END IF;
  CREATE INDEX IF NOT EXISTS idx_exam_event_outbox_pending ON exam_event_outbox(occurred_at,event_id) WHERE delivered=false;
  SELECT indexdef INTO idx_def FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_exam_event_outbox_pending';
  IF idx_def IS NULL OR idx_def NOT LIKE '%(occurred_at, event_id)%' OR idx_def NOT LIKE '%delivered = false%' THEN RAISE EXCEPTION 'outbox index has unexpected definition'; END IF;
END $$;
