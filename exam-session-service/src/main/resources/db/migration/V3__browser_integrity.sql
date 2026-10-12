ALTER TABLE exam_sessions ADD COLUMN integrity_policy jsonb NOT NULL DEFAULT
'{"monitorTabSwitch":false,"monitorWindowBlur":false,"requireFullscreen":false,"monitorCopy":false,"monitorPaste":false,"monitorNetwork":false,"warningPolicy":"LOG_ONLY","warningEventLimit":5}';
ALTER TABLE exam_sessions ADD CONSTRAINT session_integrity_policy_object CHECK (jsonb_typeof(integrity_policy)='object');
-- Identity binding uses only this service's local session table; no cross-service foreign key.
ALTER TABLE exam_sessions ADD CONSTRAINT session_integrity_identity UNIQUE(id,exam_id,user_id);
CREATE TABLE integrity_events (
 id uuid PRIMARY KEY,
 client_event_id uuid NOT NULL,
 exam_id uuid NOT NULL,
 session_id uuid NOT NULL,
 user_id uuid NOT NULL,
 type varchar(32) NOT NULL CHECK(type IN ('TAB_SWITCH','WINDOW_BLUR','EXIT_FULLSCREEN','COPY','PASTE','NETWORK_DISCONNECT','NETWORK_RECONNECT','FULLSCREEN_ENTER','WINDOW_FOCUS','PAGE_RELOAD','SESSION_RESUME')),
 occurred_at timestamptz,
 received_at timestamptz NOT NULL,
 severity varchar(16) NOT NULL CHECK(severity IN ('LOW','MEDIUM')),
 metadata jsonb CHECK(metadata IS NULL OR jsonb_typeof(metadata)='object'),
 source varchar(16) NOT NULL CHECK(source='BROWSER'),
 CONSTRAINT integrity_event_session_identity FOREIGN KEY(session_id,exam_id,user_id) REFERENCES exam_sessions(id,exam_id,user_id),
 CONSTRAINT integrity_event_retry UNIQUE(session_id,client_event_id)
);
CREATE INDEX idx_integrity_session_received ON integrity_events(session_id,received_at,id);
CREATE INDEX idx_integrity_exam_received ON integrity_events(exam_id,received_at,id);
CREATE INDEX idx_integrity_type_received ON integrity_events(exam_id,type,received_at);
CREATE INDEX idx_integrity_occurred ON integrity_events(occurred_at);
CREATE FUNCTION reject_integrity_event_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Integrity evidence is immutable'; END;
$$;
CREATE TRIGGER integrity_event_immutable BEFORE UPDATE ON integrity_events
FOR EACH ROW EXECUTE FUNCTION reject_integrity_event_update();

-- Local runtime gate serializes whole-Exam integrity lock with Start in this service.
CREATE TABLE integrity_exam_locks (
 exam_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL,
 locked_at timestamptz NOT NULL,
 reason text
);

CREATE TABLE session_integrity_audit (
 id uuid PRIMARY KEY,
 session_id uuid NOT NULL REFERENCES exam_sessions(id),
 exam_id uuid NOT NULL,
 actor_user_id uuid,
 actor_type varchar(16) NOT NULL CHECK(actor_type IN ('SYSTEM','EXAM_STAFF')),
 action varchar(64) NOT NULL,
 trigger_event_id uuid REFERENCES integrity_events(id),
 event_count bigint,
 occurred_at timestamptz NOT NULL,
 metadata text
);
CREATE INDEX idx_session_integrity_audit_session ON session_integrity_audit(session_id,occurred_at,id);
CREATE INDEX idx_session_integrity_audit_exam ON session_integrity_audit(exam_id,occurred_at,id);
CREATE TRIGGER session_integrity_audit_immutable BEFORE UPDATE ON session_integrity_audit
FOR EACH ROW EXECUTE FUNCTION reject_integrity_event_update();
