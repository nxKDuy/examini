ALTER TABLE public_question_items ADD COLUMN subject_name VARCHAR(255);
ALTER TABLE public_question_items ADD COLUMN category_name VARCHAR(255);
ALTER TABLE public_question_items ADD COLUMN category_parent_id UUID;
ALTER TABLE public_question_items ADD COLUMN category_path TEXT NOT NULL DEFAULT '[]';

UPDATE public_question_items i
SET subject_name=s.name,
    category_name=c.name,
    category_parent_id=parent.id,
    category_path=COALESCE((SELECT jsonb_agg(x.name ORDER BY x.depth DESC)::text
        FROM (WITH RECURSIVE ancestors(id,name,parent_id,depth) AS (
            SELECT c0.id,c0.name,c0.parent_category_id,0 FROM categories c0 WHERE c0.id=i.category_id
            UNION ALL SELECT p.id,p.name,p.parent_category_id,a.depth+1 FROM categories p JOIN ancestors a ON p.id=a.parent_id
        ) SELECT name,depth FROM ancestors) x),'[]')
FROM categories c LEFT JOIN subjects s ON s.id=c.subject_id LEFT JOIN categories parent ON parent.id=c.parent_category_id
WHERE c.id=i.category_id;
UPDATE public_question_items i SET subject_name=s.name
FROM subjects s WHERE i.subject_id=s.id AND i.subject_name IS NULL;

CREATE TABLE public_question_ratings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id UUID NOT NULL REFERENCES public_questions(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL,
    rating NUMERIC NOT NULL CHECK (rating >= 0 AND rating <= 10),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_public_rating_actor UNIQUE(publication_id,actor_user_id)
);
CREATE INDEX idx_public_question_ratings_post_window ON public_question_ratings(publication_id,created_at);

CREATE TABLE public_question_view_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    publication_id UUID NOT NULL REFERENCES public_questions(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL,
    event_day DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_public_view_actor_day UNIQUE(publication_id,actor_user_id,event_day)
);
CREATE INDEX idx_public_question_views_window ON public_question_view_events(publication_id,event_day,actor_user_id);
