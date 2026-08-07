-- V1: Khởi tạo schema cho exam-session-service

CREATE TABLE IF NOT EXISTS exam_sessions (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id    UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    start_time TIMESTAMP    NOT NULL,
    end_time   TIMESTAMP,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'AUTO_SUBMITTED', 'CANCELLED')),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_user ON exam_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_exam ON exam_sessions(exam_id);

CREATE TABLE IF NOT EXISTS session_answers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID      NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    question_id     UUID      NOT NULL,
    answer_content  TEXT,
    last_updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_answers_session ON session_answers(session_id);

CREATE TABLE IF NOT EXISTS proctoring_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID         NOT NULL REFERENCES exam_sessions(id) ON DELETE CASCADE,
    event_type  VARCHAR(30)  NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_logs_session ON proctoring_logs(session_id);
