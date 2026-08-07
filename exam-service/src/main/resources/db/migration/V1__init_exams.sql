-- V1: Khởi tạo schema cho exam-service

CREATE TABLE IF NOT EXISTS exams (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code             VARCHAR(50)  NOT NULL UNIQUE,
    title            VARCHAR(255) NOT NULL,
    description      TEXT,
    duration_minutes INT          NOT NULL,
    start_time       TIMESTAMP,
    end_time         TIMESTAMP,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'COMPLETED')),
    created_by       UUID         NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exams_code ON exams(code);
CREATE INDEX IF NOT EXISTS idx_exams_creator ON exams(created_by);

-- Bảng phụ ánh xạ Exam và Question (chỉ lưu UUID từ question-bank-service)
CREATE TABLE IF NOT EXISTS exam_questions (
    exam_id     UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    question_id UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_exam_questions_exam ON exam_questions(exam_id);
