-- Existing exams retain Phase 1 behavior: all monitors disabled, no automatic sanctions.
ALTER TABLE exams ADD COLUMN integrity_policy jsonb NOT NULL DEFAULT
'{"monitorTabSwitch":false,"monitorWindowBlur":false,"requireFullscreen":false,"monitorCopy":false,"monitorPaste":false,"monitorNetwork":false,"warningPolicy":"LOG_ONLY","warningEventLimit":5}';
ALTER TABLE exams ADD CONSTRAINT exam_integrity_policy_object CHECK (jsonb_typeof(integrity_policy)='object');
ALTER TABLE exams ADD COLUMN integrity_locked boolean NOT NULL DEFAULT false;
