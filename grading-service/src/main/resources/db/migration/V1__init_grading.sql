-- V1: Khởi tạo schema cho grading-service

CREATE TABLE IF NOT EXISTS exam_results (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  UUID         NOT NULL UNIQUE,
    exam_id     UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    total_score REAL,
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'GRADED')),
    graded_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_results_user ON exam_results(user_id);

CREATE TABLE IF NOT EXISTS question_grades (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    result_id   UUID NOT NULL REFERENCES exam_results(id) ON DELETE CASCADE,
    question_id UUID NOT NULL,
    score       REAL NOT NULL,
    feedback    TEXT
);

CREATE INDEX IF NOT EXISTS idx_qgrades_result ON question_grades(result_id);
