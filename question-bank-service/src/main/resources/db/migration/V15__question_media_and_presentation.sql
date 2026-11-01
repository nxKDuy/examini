-- Richer questions: images/audio attached to a question or its options, reading passages shared by several
-- questions, listening play limits, writing word limits and a SPEAKING type answered with a voice recording.
ALTER TABLE questions ADD COLUMN presentation JSONB CHECK (presentation IS NULL OR jsonb_typeof(presentation) = 'object');

ALTER TABLE questions DROP CONSTRAINT questions_question_type_check;
ALTER TABLE questions ADD CONSTRAINT questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING'));
ALTER TABLE public_questions DROP CONSTRAINT public_questions_question_type_check;
ALTER TABLE public_questions ADD CONSTRAINT public_questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING'));
ALTER TABLE organization_questions DROP CONSTRAINT organization_questions_question_type_check;
ALTER TABLE organization_questions ADD CONSTRAINT organization_questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING'));

-- Uploaded files live on a volume; rows keep who uploaded what. Ids are random, never derived from names.
CREATE TABLE question_media (
    id            UUID PRIMARY KEY,
    owner_id      UUID         NOT NULL,
    kind          VARCHAR(10)  NOT NULL CHECK (kind IN ('IMAGE','AUDIO')),
    content_type  VARCHAR(60)  NOT NULL,
    size_bytes    INT          NOT NULL CHECK (size_bytes > 0),
    name          VARCHAR(255),
    storage_path  VARCHAR(400) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_question_media_owner ON question_media(owner_id, created_at DESC);

-- Spreadsheet imports (Excel template / CSV) are staged like documents.
ALTER TABLE question_import_jobs DROP CONSTRAINT question_import_jobs_file_kind_check;
ALTER TABLE question_import_jobs ADD CONSTRAINT question_import_jobs_file_kind_check
    CHECK (file_kind IN ('DOCX','PDF','IMAGE','AI','XLSX','CSV'));
