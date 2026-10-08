-- Keep historical snapshots/reviews byte-for-byte and record the previous live state.
CREATE TABLE question_lifecycle_migrations (
    question_id UUID PRIMARY KEY REFERENCES questions(id),
    previous_status VARCHAR(30) NOT NULL,
    migrated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO question_lifecycle_migrations(question_id,previous_status) SELECT id,status FROM questions;
ALTER TABLE questions DROP CONSTRAINT questions_status_check;
UPDATE questions SET status=CASE WHEN status='APPROVED' THEN 'ACTIVE'
    WHEN status IN ('PENDING_REVIEW','REJECTED') THEN 'DRAFT' ELSE status END;
ALTER TABLE questions ADD CONSTRAINT questions_status_check CHECK(status IN ('DRAFT','ACTIVE','ARCHIVED'));
-- Organization authority belongs to organization-service and is checked online by Question Bank.
DO $$ DECLARE c RECORD; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='question_banks'::regclass
     AND contype='c' AND pg_get_constraintdef(oid) LIKE '%owner_user_id%' LOOP
  EXECUTE format('ALTER TABLE question_banks DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
UPDATE question_banks SET status='ACTIVE' WHERE type='ORGANIZATION';
ALTER TABLE question_banks ADD CONSTRAINT bank_owner_shape CHECK(
 (type='PERSONAL' AND owner_user_id IS NOT NULL AND organization_id IS NULL AND status='ACTIVE') OR
 (type='ORGANIZATION' AND owner_user_id IS NULL AND organization_id IS NOT NULL AND status='ACTIVE' AND NOT is_default) OR
 (type IN ('SYSTEM','PUBLIC') AND owner_user_id IS NULL AND organization_id IS NULL AND status='ACTIVE'));
-- Direct Public publication has no reviewer. Existing legacy verification audit remains.
ALTER TABLE public_contributions DROP CONSTRAINT public_contributions_status_check;
DO $$ DECLARE c RECORD; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='public_contributions'::regclass
     AND contype='c' AND pg_get_constraintdef(oid) LIKE '%reviewer_id%' LOOP
  EXECUTE format('ALTER TABLE public_contributions DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE public_contributions ADD CONSTRAINT public_contributions_status_check
 CHECK(status IN ('PUBLIC_PENDING','PUBLIC_VERIFIED','PUBLIC_REJECTED','PUBLIC_PUBLISHED'));
ALTER TABLE public_contributions ADD CONSTRAINT public_audit_shape CHECK(
 (status IN ('PUBLIC_PENDING','PUBLIC_PUBLISHED') AND reviewer_id IS NULL AND reviewed_at IS NULL) OR
 (status IN ('PUBLIC_VERIFIED','PUBLIC_REJECTED') AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL));
CREATE TABLE organization_contributions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), bank_id UUID NOT NULL,
 bank_type VARCHAR(30) NOT NULL DEFAULT 'ORGANIZATION' CHECK(bank_type='ORGANIZATION'),
 source_question_id UUID NOT NULL, source_question_version BIGINT NOT NULL CHECK(source_question_version>0),
 source_bank_id UUID NOT NULL REFERENCES question_banks(id), source_owner_user_id UUID NOT NULL,
 snapshot TEXT NOT NULL, status VARCHAR(30) NOT NULL CHECK(status IN ('PENDING_REVIEW','APPROVED','REJECTED')),
 submitted_by UUID NOT NULL, submitted_at TIMESTAMP NOT NULL,
 reviewer_id UUID, review_note VARCHAR(5000), reviewed_at TIMESTAMP, lock_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(bank_id,source_question_id,source_question_version),
 FOREIGN KEY(bank_id,bank_type) REFERENCES question_banks(id,type),
 FOREIGN KEY(source_question_id,source_question_version) REFERENCES question_versions(question_id,version_number),
 CHECK((status='PENDING_REVIEW' AND reviewer_id IS NULL AND reviewed_at IS NULL) OR
       (status<>'PENDING_REVIEW' AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)),
 CHECK(status<>'REJECTED' OR (review_note IS NOT NULL AND length(trim(review_note))>0))
);
CREATE INDEX idx_org_contributions_bank ON organization_contributions(bank_id,status);
CREATE TABLE organization_questions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), bank_id UUID NOT NULL,
 bank_type VARCHAR(30) NOT NULL DEFAULT 'ORGANIZATION' CHECK(bank_type='ORGANIZATION'),
 contribution_id UUID NOT NULL UNIQUE REFERENCES organization_contributions(id),
 source_question_id UUID NOT NULL, source_question_version BIGINT NOT NULL,
 source_bank_id UUID NOT NULL REFERENCES question_banks(id), source_owner_user_id UUID NOT NULL,
 snapshot TEXT NOT NULL, question_type VARCHAR(30) NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY')),
 difficulty_level VARCHAR(10) NOT NULL CHECK(difficulty_level IN ('EASY','MEDIUM','HARD')),
 approved_by UUID NOT NULL, approved_at TIMESTAMP NOT NULL,
 FOREIGN KEY(bank_id,bank_type) REFERENCES question_banks(id,type),
 FOREIGN KEY(source_question_id,source_question_version) REFERENCES question_versions(question_id,version_number)
);
CREATE INDEX idx_org_questions_bank ON organization_questions(bank_id,approved_at);
ALTER TABLE question_lineage ADD COLUMN source_organization_question_id UUID REFERENCES organization_questions(id);
ALTER TABLE question_lineage ADD CONSTRAINT org_lineage_shape CHECK(
 (source_bank_type='ORGANIZATION')=(source_organization_question_id IS NOT NULL));
CREATE INDEX idx_org_questions_filter ON organization_questions(bank_id,question_type,difficulty_level);
