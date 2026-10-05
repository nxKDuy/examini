-- Existing questions are unreviewed, never implicitly approved.
ALTER TABLE questions
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_REVIEW','APPROVED','REJECTED','ARCHIVED')),
    ADD COLUMN scope VARCHAR(30) NOT NULL DEFAULT 'PERSONAL' CHECK (scope IN ('PERSONAL','ORGANIZATION','SYSTEM')),
    ADD COLUMN creation_method VARCHAR(30) NOT NULL DEFAULT 'MANUAL' CHECK (creation_method IN ('MANUAL','IMPORT','AI_GENERATED','COPIED')),
    ADD COLUMN source_type VARCHAR(30) NOT NULL DEFAULT 'NONE' CHECK (source_type IN ('NONE','USER_DOCUMENT','EXTERNAL_WEB','ORGANIZATION_BANK','PUBLIC_BANK','SYSTEM_BANK')),
    ADD COLUMN source_ref_id VARCHAR(255),
    ADD COLUMN source_label VARCHAR(500),
    ADD COLUMN source_url VARCHAR(2000),
    ADD COLUMN source_excerpt TEXT,
    ADD COLUMN expected_answer TEXT,
    ADD COLUMN question_version BIGINT NOT NULL DEFAULT 1 CHECK (question_version > 0),
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;
UPDATE questions SET source_label = source, updated_at = created_at;
CREATE INDEX idx_questions_owner_status ON questions(created_by, status);
CREATE INDEX idx_questions_eligibility ON questions(category_id, difficulty_level, status);

CREATE TABLE question_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id UUID NOT NULL REFERENCES questions(id),
    version_number BIGINT NOT NULL CHECK (version_number > 0),
    snapshot TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    changed_by UUID NOT NULL,
    UNIQUE(question_id, version_number)
);
CREATE TABLE question_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id UUID NOT NULL REFERENCES questions(id),
    question_version BIGINT NOT NULL,
    reviewer_id UUID NOT NULL,
    decision VARCHAR(30) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    review_note TEXT,
    reviewed_at TIMESTAMP NOT NULL,
    FOREIGN KEY(question_id, question_version) REFERENCES question_versions(question_id, version_number)
);
CREATE INDEX idx_reviews_question ON question_reviews(question_id, reviewed_at);

-- Full version-one snapshots preserve legacy option identities and answer keys.
INSERT INTO question_versions(question_id, version_number, snapshot, created_at, changed_by)
SELECT q.id, 1, jsonb_build_object(
    'id', q.id, 'categoryId', q.category_id, 'categoryName', c.name,
    'content', q.content, 'questionType', q.question_type, 'difficultyLevel', q.difficulty_level,
    'source', q.source, 'createdAt', q.created_at, 'updatedAt', q.updated_at,
    'createdBy', q.created_by, 'questionVersion', 1, 'status', 'DRAFT', 'scope', 'PERSONAL',
    'creationMethod', 'MANUAL', 'sourceType', 'NONE', 'sourceLabel', q.source,
    'options', COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'id', o.id, 'content', o.content, 'correct', o.is_correct, 'displayOrder', o.display_order
    ) ORDER BY o.display_order, o.id) FROM question_options o WHERE o.question_id = q.id), '[]'::jsonb)
)::text, q.created_at, q.created_by
FROM questions q JOIN categories c ON c.id = q.category_id;
