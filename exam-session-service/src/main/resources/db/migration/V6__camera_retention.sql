-- Camera media (snapshots and video) are deleted a fixed number of days after the attempt's deadline.
-- The violation log itself (type, time, measurements) stays as the audit trail; only the media go.
CREATE TABLE camera_media_purges (
    session_id  UUID PRIMARY KEY REFERENCES exam_sessions(id),
    exam_id     UUID         NOT NULL,
    purged_at   TIMESTAMPTZ  NOT NULL,
    files       INT          NOT NULL,
    bytes       BIGINT       NOT NULL
);
CREATE INDEX idx_camera_media_purges_exam ON camera_media_purges(exam_id);
