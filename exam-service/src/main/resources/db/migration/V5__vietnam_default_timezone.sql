-- Vietnam (Asia/Ho_Chi_Minh) is the default display/scheduling zone for exams. Instants stay UTC, so existing
-- schedules keep their exact moment; only how they are shown and entered changes. On a fresh database the
-- column is created later by R__exam_scheduling with the new default, so this is a no-op there.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema=current_schema() AND table_name='exams' AND column_name='timezone') THEN
    ALTER TABLE exams ALTER COLUMN timezone SET DEFAULT 'Asia/Ho_Chi_Minh';
    UPDATE exams SET timezone='Asia/Ho_Chi_Minh' WHERE timezone='UTC';
  END IF;
END $$;
