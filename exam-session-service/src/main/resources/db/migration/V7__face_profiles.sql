-- Face verification at exam entry (only for exams with camera proctoring).
-- The first camera exam of a student stores a face profile (descriptor + photo); later exams compare the
-- enrollment photo with it. The browser computes descriptors; the server keeps them and measures the distance.
CREATE TABLE face_profiles (
    user_id            UUID PRIMARY KEY,
    descriptor         JSONB        NOT NULL CHECK (jsonb_typeof(descriptor) = 'array'),
    photo_path         VARCHAR(400) NOT NULL,
    source_session_id  UUID         REFERENCES exam_sessions(id),
    created_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE camera_identity_checks (
    session_id          UUID PRIMARY KEY REFERENCES exam_sessions(id),
    user_id             UUID             NOT NULL,
    outcome             VARCHAR(20)      NOT NULL CHECK (outcome IN ('ENROLLED','MATCH','MISMATCH')),
    distance            DOUBLE PRECISION,
    threshold           DOUBLE PRECISION NOT NULL,
    attempts            INT              NOT NULL DEFAULT 0,
    checked_at          TIMESTAMPTZ      NOT NULL,
    profile_created_at  TIMESTAMPTZ
);

ALTER TABLE camera_events DROP CONSTRAINT camera_events_type_check;
ALTER TABLE camera_events ADD CONSTRAINT camera_events_type_check CHECK (type IN
    ('REFERENCE','NO_FACE','MULTIPLE_FACES','FACE_MISMATCH','LOOKING_AWAY','CAMERA_OFF','CAMERA_ON','PROFILE_MISMATCH'));
