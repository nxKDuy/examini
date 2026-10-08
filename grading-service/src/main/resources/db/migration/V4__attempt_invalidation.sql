-- Attempt invalidation is mirrored from the source-owned Session; results/publications/audits are retained.
ALTER TABLE exam_results ADD COLUMN invalidated_at TIMESTAMPTZ;
ALTER TABLE exam_results ADD COLUMN invalidation_reason TEXT;
ALTER TABLE exam_results ADD CONSTRAINT ck_exam_result_invalidation CHECK(invalidated_at IS NOT NULL OR invalidation_reason IS NULL);
