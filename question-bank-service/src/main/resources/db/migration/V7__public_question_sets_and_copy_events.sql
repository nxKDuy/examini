CREATE TABLE question_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_id UUID NOT NULL REFERENCES question_banks(id),
    owner_user_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    current_version BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_question_sets_owner ON question_sets(owner_user_id,status,updated_at DESC);

CREATE TABLE question_set_versions (
    question_set_id UUID NOT NULL REFERENCES question_sets(id) ON DELETE CASCADE,
    version_number BIGINT NOT NULL,
    snapshot TEXT NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY(question_set_id,version_number)
);

ALTER TABLE public_questions ADD COLUMN post_type VARCHAR(30) NOT NULL DEFAULT 'SINGLE_QUESTION'
    CHECK (post_type IN ('SINGLE_QUESTION','QUESTION_SET'));
ALTER TABLE public_questions ADD COLUMN title VARCHAR(200);
ALTER TABLE public_questions ADD COLUMN description VARCHAR(2000);
ALTER TABLE public_questions ADD COLUMN post_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE public_questions ADD COLUMN item_count INTEGER NOT NULL DEFAULT 1 CHECK (item_count > 0);
ALTER TABLE public_questions ADD COLUMN category_id UUID;
ALTER TABLE public_questions ADD COLUMN subject_id UUID;
ALTER TABLE public_questions ALTER COLUMN contribution_id DROP NOT NULL;
ALTER TABLE public_questions ADD COLUMN question_set_id UUID REFERENCES question_sets(id);
ALTER TABLE public_questions ADD COLUMN question_set_version BIGINT;
CREATE UNIQUE INDEX uq_public_question_set_version ON public_questions(question_set_id,question_set_version) WHERE question_set_id IS NOT NULL;
UPDATE public_questions SET title=left(COALESCE(NULLIF(snapshot::jsonb->>'content',''),'Question'),200)
WHERE title IS NULL;
UPDATE public_questions p SET category_id=NULLIF(p.snapshot::jsonb->>'categoryId','')::uuid,
    subject_id=c.subject_id FROM categories c WHERE c.id=NULLIF(p.snapshot::jsonb->>'categoryId','')::uuid;

CREATE TABLE public_question_items (
    id UUID PRIMARY KEY,
    publication_id UUID NOT NULL REFERENCES public_questions(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    source_question_id UUID NOT NULL,
    source_question_version BIGINT NOT NULL,
    source_bank_id UUID NOT NULL,
    source_owner_user_id UUID NOT NULL,
    snapshot TEXT NOT NULL,
    search_content TEXT NOT NULL,
    question_type VARCHAR(30) NOT NULL,
    difficulty_level VARCHAR(10) NOT NULL,
    category_id UUID,
    subject_id UUID,
    UNIQUE(publication_id,position)
);
CREATE INDEX idx_public_question_items_discovery ON public_question_items(subject_id,category_id,question_type,difficulty_level);

-- Preserve every existing PublicQuestion resource ID so old Exam snapshots and batch requests keep resolving.
INSERT INTO public_question_items(id,publication_id,position,source_question_id,source_question_version,source_bank_id,
    source_owner_user_id,snapshot,search_content,question_type,difficulty_level,category_id,subject_id)
SELECT p.id,p.id,0,p.source_question_id,p.source_question_version,p.source_bank_id,p.source_owner_user_id,
    p.snapshot,p.search_content,p.question_type,p.difficulty_level,
    NULLIF(p.snapshot::jsonb->>'categoryId','')::uuid,c.subject_id
FROM public_questions p
LEFT JOIN categories c ON c.id=NULLIF(p.snapshot::jsonb->>'categoryId','')::uuid;

CREATE TABLE public_question_copy_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id UUID NOT NULL REFERENCES public_questions(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL,
    copied_at TIMESTAMP NOT NULL DEFAULT NOW(),
    request_key UUID,
    UNIQUE(publication_id,actor_user_id,request_key)
);
CREATE INDEX idx_public_copy_events_window ON public_question_copy_events(publication_id,copied_at,actor_user_id);
