-- Camera proctoring (evidence for proctors, never an automatic verdict).
-- Face analysis runs in the student's browser; the server stores the reference photo, violation snapshots
-- (with the reason the browser detected) and the compressed WebM recording in ordered chunks on disk.
CREATE TABLE camera_events (
    id               UUID PRIMARY KEY,
    session_id       UUID         NOT NULL REFERENCES exam_sessions(id),
    exam_id          UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    client_event_id  UUID         NOT NULL,
    type             VARCHAR(30)  NOT NULL CHECK (type IN ('REFERENCE','NO_FACE','MULTIPLE_FACES','FACE_MISMATCH','LOOKING_AWAY','CAMERA_OFF','CAMERA_ON')),
    occurred_at      TIMESTAMPTZ,
    received_at      TIMESTAMPTZ  NOT NULL,
    detail           JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(detail)='object'),
    snapshot_path    VARCHAR(400),
    snapshot_bytes   INT,
    UNIQUE (session_id, client_event_id)
);
CREATE INDEX idx_camera_events_session ON camera_events(session_id, received_at);
CREATE INDEX idx_camera_events_exam ON camera_events(exam_id, received_at);

CREATE TABLE camera_recordings (
    session_id     UUID PRIMARY KEY REFERENCES exam_sessions(id),
    exam_id        UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    mime_type      VARCHAR(60)  NOT NULL,
    chunks         INT          NOT NULL DEFAULT 0,
    bytes          BIGINT       NOT NULL DEFAULT 0,
    started_at     TIMESTAMPTZ  NOT NULL,
    last_chunk_at  TIMESTAMPTZ
);
CREATE INDEX idx_camera_recordings_exam ON camera_recordings(exam_id);
