CREATE TABLE question_banks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(30) NOT NULL CHECK(type IN ('PERSONAL','ORGANIZATION','SYSTEM','PUBLIC')),
    owner_user_id UUID,
    organization_id UUID,
    name VARCHAR(200) NOT NULL CHECK(length(trim(name)) > 0),
    status VARCHAR(30) NOT NULL CHECK(status IN ('ACTIVE','PENDING_AUTHORIZATION')),
    is_default BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(id,type),
    CHECK((type='PERSONAL' AND owner_user_id IS NOT NULL AND organization_id IS NULL AND status='ACTIVE')
       OR (type='ORGANIZATION' AND owner_user_id IS NULL AND organization_id IS NOT NULL AND status='PENDING_AUTHORIZATION' AND NOT is_default)
       OR (type IN ('SYSTEM','PUBLIC') AND owner_user_id IS NULL AND organization_id IS NULL AND status='ACTIVE'))
);
CREATE UNIQUE INDEX uq_default_personal_bank ON question_banks(owner_user_id) WHERE type='PERSONAL' AND is_default;
CREATE UNIQUE INDEX uq_default_public_bank ON question_banks(type) WHERE type='PUBLIC' AND is_default;
CREATE INDEX idx_banks_organization ON question_banks(organization_id);
-- Phase 1 never activated Organization/System. Refuse ambiguous historical ownership.
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM questions WHERE scope <> 'PERSONAL') THEN
        RAISE EXCEPTION 'Non-personal legacy Question requires explicit bank ownership migration';
    END IF;
END $$;
INSERT INTO question_banks(type,owner_user_id,name,status,is_default)
SELECT 'PERSONAL',created_by,'Personal Bank','ACTIVE',true FROM questions GROUP BY created_by;
INSERT INTO question_banks(type,name,status,is_default) VALUES('PUBLIC','Verified Public Bank','ACTIVE',true);
ALTER TABLE questions ADD COLUMN bank_id UUID;
UPDATE questions q SET bank_id=b.id FROM question_banks b WHERE b.type='PERSONAL' AND b.owner_user_id=q.created_by AND b.is_default;
ALTER TABLE questions ALTER COLUMN bank_id SET NOT NULL;
ALTER TABLE questions ADD CONSTRAINT fk_question_bank_scope FOREIGN KEY(bank_id,scope) REFERENCES question_banks(id,type);
CREATE INDEX idx_questions_bank_status ON questions(bank_id,status);
-- Do not rewrite historical JSON snapshots, review records, option identities or versions.
CREATE TABLE public_contributions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_question_id UUID NOT NULL,
    source_question_version BIGINT NOT NULL CHECK(source_question_version>0),
    source_bank_id UUID NOT NULL REFERENCES question_banks(id),
    source_owner_user_id UUID NOT NULL,
    submitted_by UUID NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    snapshot TEXT NOT NULL,
    status VARCHAR(30) NOT NULL CHECK(status IN ('PUBLIC_PENDING','PUBLIC_VERIFIED','PUBLIC_REJECTED')),
    reviewer_id UUID,
    review_note VARCHAR(5000),
    reviewed_at TIMESTAMP,
    lock_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(source_question_id,source_question_version),
    FOREIGN KEY(source_question_id,source_question_version) REFERENCES question_versions(question_id,version_number),
    CHECK((status='PUBLIC_PENDING' AND reviewer_id IS NULL AND reviewed_at IS NULL)
       OR (status<>'PUBLIC_PENDING' AND reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)),
    CHECK(status<>'PUBLIC_REJECTED' OR (review_note IS NOT NULL AND length(trim(review_note))>0))
);
CREATE INDEX idx_contributions_submitter ON public_contributions(submitted_by,status);
CREATE TABLE public_questions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_id UUID NOT NULL,
    bank_type VARCHAR(30) NOT NULL DEFAULT 'PUBLIC' CHECK(bank_type='PUBLIC'),
    contribution_id UUID NOT NULL UNIQUE REFERENCES public_contributions(id),
    source_question_id UUID NOT NULL,
    source_question_version BIGINT NOT NULL CHECK(source_question_version>0),
    source_bank_id UUID NOT NULL REFERENCES question_banks(id),
    source_owner_user_id UUID NOT NULL,
    snapshot TEXT NOT NULL,
    question_type VARCHAR(30) NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY')),
    difficulty_level VARCHAR(10) NOT NULL CHECK(difficulty_level IN ('EASY','MEDIUM','HARD')),
    verified_by UUID NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    FOREIGN KEY(source_question_id,source_question_version) REFERENCES question_versions(question_id,version_number),
    FOREIGN KEY(bank_id,bank_type) REFERENCES question_banks(id,type)
);
CREATE INDEX idx_public_questions_filter ON public_questions(bank_id,question_type,difficulty_level);
CREATE TABLE question_lineage (
    question_id UUID PRIMARY KEY REFERENCES questions(id),
    source_question_id UUID NOT NULL,
    source_question_version BIGINT NOT NULL CHECK(source_question_version>0),
    source_bank_id UUID NOT NULL,
    source_bank_type VARCHAR(30) NOT NULL CHECK(source_bank_type IN ('PERSONAL','ORGANIZATION','SYSTEM','PUBLIC')),
    source_public_question_id UUID REFERENCES public_questions(id),
    copied_at TIMESTAMP NOT NULL,
    copied_by UUID NOT NULL,
    FOREIGN KEY(source_question_id,source_question_version) REFERENCES question_versions(question_id,version_number),
    CHECK((source_bank_type='PUBLIC') = (source_public_question_id IS NOT NULL)),
    FOREIGN KEY(source_bank_id,source_bank_type) REFERENCES question_banks(id,type)
);
CREATE INDEX idx_lineage_source ON question_lineage(source_question_id,source_question_version);
