ALTER TABLE public_questions ADD COLUMN source_owner_display_name VARCHAR(200);
ALTER TABLE public_questions ADD COLUMN search_content TEXT;
ALTER TABLE public_questions ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE public_questions ADD COLUMN archived_by UUID;
ALTER TABLE public_questions ADD COLUMN archived_at TIMESTAMP;

UPDATE public_questions
SET source_owner_display_name = 'Unknown user',
    search_content = COALESCE(snapshot::jsonb ->> 'content', '');

ALTER TABLE public_questions ALTER COLUMN source_owner_display_name SET NOT NULL;
ALTER TABLE public_questions ALTER COLUMN search_content SET NOT NULL;
ALTER TABLE public_questions ADD CONSTRAINT public_questions_status_check
    CHECK (status IN ('PUBLISHED','ARCHIVED'));
ALTER TABLE public_questions ADD CONSTRAINT public_questions_archive_shape CHECK (
    (status='PUBLISHED' AND archived_by IS NULL AND archived_at IS NULL) OR
    (status='ARCHIVED' AND archived_by IS NOT NULL AND archived_at IS NOT NULL));
CREATE INDEX idx_public_questions_discovery
    ON public_questions(status,question_type,difficulty_level,verified_at DESC);
