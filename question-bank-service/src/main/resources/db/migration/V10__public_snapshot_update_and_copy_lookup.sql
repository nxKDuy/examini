ALTER TABLE public_questions ADD COLUMN content_updated_at TIMESTAMP;
UPDATE public_questions SET content_updated_at = verified_at;
ALTER TABLE public_questions ALTER COLUMN content_updated_at SET NOT NULL;

CREATE INDEX idx_lineage_public_copy_actor ON question_lineage(source_public_question_id, copied_by)
    WHERE source_public_question_id IS NOT NULL;
CREATE INDEX idx_lineage_org_copy_actor ON question_lineage(source_organization_question_id, copied_by)
    WHERE source_organization_question_id IS NOT NULL;
