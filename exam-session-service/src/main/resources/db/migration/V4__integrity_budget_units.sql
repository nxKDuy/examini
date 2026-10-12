-- Budget accounting is decided once at ingest and stored with the immutable evidence row.
ALTER TABLE integrity_events ADD COLUMN counted boolean NOT NULL DEFAULT false;
ALTER TABLE integrity_events ADD COLUMN not_counted_reason varchar(24)
  CHECK(not_counted_reason IN ('NOT_BUDGET_SIGNAL','COALESCED','STALE'));
ALTER TABLE integrity_events ADD COLUMN coalesced_into uuid REFERENCES integrity_events(id);
ALTER TABLE integrity_events ADD COLUMN effective_at timestamptz;
-- Preserve counts of attempts already in progress under the previous rule, minus network signals.
ALTER TABLE integrity_events DISABLE TRIGGER integrity_event_immutable;
UPDATE integrity_events SET effective_at=received_at,
  counted=type IN ('TAB_SWITCH','WINDOW_BLUR','EXIT_FULLSCREEN','PAGE_RELOAD'),
  not_counted_reason=CASE WHEN type IN ('TAB_SWITCH','WINDOW_BLUR','EXIT_FULLSCREEN','PAGE_RELOAD') THEN NULL ELSE 'NOT_BUDGET_SIGNAL' END;
ALTER TABLE integrity_events ENABLE TRIGGER integrity_event_immutable;
ALTER TABLE integrity_events ALTER COLUMN effective_at SET NOT NULL;
ALTER TABLE integrity_events ADD CONSTRAINT integrity_event_count_reason
  CHECK((counted AND not_counted_reason IS NULL) OR (NOT counted AND not_counted_reason IS NOT NULL));
CREATE INDEX idx_integrity_events_budget ON integrity_events(session_id,effective_at) WHERE counted;
