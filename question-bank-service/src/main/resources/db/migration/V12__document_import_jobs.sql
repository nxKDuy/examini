-- Document import (DOCX/PDF/image OCR): staged, owner-only review before Personal DRAFT import.
CREATE TABLE question_import_jobs (
    id                 UUID PRIMARY KEY,
    owner_user_id      UUID         NOT NULL,
    file_name          VARCHAR(255) NOT NULL,
    file_kind          VARCHAR(10)  NOT NULL CHECK (file_kind IN ('DOCX','PDF','IMAGE')),
    file_size          BIGINT       NOT NULL,
    page_count         INT          NOT NULL DEFAULT 0,
    ocr_page_count     INT          NOT NULL DEFAULT 0,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PROCESSING','READY','FAILED','DISCARDED')),
    failure_reason     VARCHAR(1000),
    warnings           TEXT         NOT NULL DEFAULT '[]',
    category_id        UUID,
    default_difficulty VARCHAR(10)  NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);
CREATE INDEX idx_question_import_jobs_owner ON question_import_jobs(owner_user_id, created_at DESC);

CREATE TABLE question_import_items (
    id                   UUID PRIMARY KEY,
    job_id               UUID         NOT NULL REFERENCES question_import_jobs(id),
    position             INT          NOT NULL,
    version              BIGINT       NOT NULL,
    content              TEXT         NOT NULL,
    question_type        VARCHAR(30)  NOT NULL,
    difficulty_level     VARCHAR(10)  NOT NULL,
    category_id          UUID,
    options              TEXT         NOT NULL DEFAULT '[]',
    expected_answer      TEXT,
    explanation          TEXT,
    source_page          INT,
    source_method        VARCHAR(10)  NOT NULL CHECK (source_method IN ('TEXT','OCR')),
    confidence           NUMERIC(3,2) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    raw_text             TEXT         NOT NULL,
    flags                TEXT         NOT NULL DEFAULT '[]',
    review_required      BOOLEAN      NOT NULL,
    reviewed             BOOLEAN      NOT NULL DEFAULT FALSE,
    excluded             BOOLEAN      NOT NULL DEFAULT FALSE,
    valid                BOOLEAN      NOT NULL DEFAULT FALSE,
    errors               TEXT         NOT NULL DEFAULT '[]',
    imported_question_id UUID,
    imported_at          TIMESTAMP,
    UNIQUE (job_id, position)
);

-- Append-only audit of the import workflow; never updated.
CREATE TABLE question_import_events (
    id            BIGSERIAL PRIMARY KEY,
    job_id        UUID        NOT NULL REFERENCES question_import_jobs(id),
    item_id       UUID,
    actor_user_id UUID        NOT NULL,
    action        VARCHAR(30) NOT NULL,
    detail        TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT timezone('UTC', CURRENT_TIMESTAMP)
);
CREATE INDEX idx_question_import_events_job ON question_import_events(job_id, id);
