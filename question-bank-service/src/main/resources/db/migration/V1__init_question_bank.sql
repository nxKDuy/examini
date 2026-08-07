-- V1: Schema cho question-bank-service

CREATE TABLE IF NOT EXISTS subjects (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT
);

CREATE TABLE IF NOT EXISTS categories (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id         UUID NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name               VARCHAR(255) NOT NULL,
    parent_category_id UUID REFERENCES categories(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_categories_subject ON categories(subject_id);

CREATE TABLE IF NOT EXISTS questions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id      UUID NOT NULL REFERENCES categories(id),
    content          TEXT NOT NULL,
    question_type    VARCHAR(30) NOT NULL CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY')),
    difficulty_level VARCHAR(10) NOT NULL CHECK (difficulty_level IN ('EASY','MEDIUM','HARD')),
    created_by       UUID NOT NULL,
    source           VARCHAR(500),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_questions_category   ON questions(category_id);
CREATE INDEX IF NOT EXISTS idx_questions_difficulty ON questions(difficulty_level);
CREATE INDEX IF NOT EXISTS idx_questions_type       ON questions(question_type);

CREATE TABLE IF NOT EXISTS question_options (
    id            UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id   UUID     NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    content       TEXT     NOT NULL,
    is_correct    BOOLEAN  NOT NULL DEFAULT FALSE,
    display_order SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_options_question ON question_options(question_id);

-- Dữ liệu mẫu: 1 môn, 1 danh mục, 2 câu hỏi
INSERT INTO subjects (name, description) VALUES
    ('Toán học', 'Các môn Toán học cơ bản và nâng cao');

-- Dùng tên variable cho câu lệnh INSERT liên tiếp (Postgres DO block)
DO $$
DECLARE
    v_subject_id UUID;
    v_category_id UUID;
    v_q1_id UUID;
    v_q2_id UUID;
BEGIN
    SELECT id INTO v_subject_id FROM subjects WHERE name = 'Toán học' LIMIT 1;

    INSERT INTO categories (subject_id, name)
    VALUES (v_subject_id, 'Đại số cơ bản') RETURNING id INTO v_category_id;

    INSERT INTO questions (category_id, content, question_type, difficulty_level, created_by, source)
    VALUES (v_category_id, '2 + 2 = ?', 'MULTIPLE_CHOICE', 'EASY',
            '00000000-0000-0000-0000-000000000001', 'Mẫu') RETURNING id INTO v_q1_id;

    INSERT INTO question_options (question_id, content, is_correct, display_order) VALUES
        (v_q1_id, '3', false, 0),
        (v_q1_id, '4', true,  1),
        (v_q1_id, '5', false, 2),
        (v_q1_id, '6', false, 3);

    INSERT INTO questions (category_id, content, question_type, difficulty_level, created_by, source)
    VALUES (v_category_id, 'x² - 4 = 0, tìm x?', 'MULTIPLE_CHOICE', 'MEDIUM',
            '00000000-0000-0000-0000-000000000001', 'Mẫu') RETURNING id INTO v_q2_id;

    INSERT INTO question_options (question_id, content, is_correct, display_order) VALUES
        (v_q2_id, 'x = 1', false, 0),
        (v_q2_id, 'x = ±2', true,  1),
        (v_q2_id, 'x = 4', false, 2),
        (v_q2_id, 'x = 0', false, 3);
END $$;
