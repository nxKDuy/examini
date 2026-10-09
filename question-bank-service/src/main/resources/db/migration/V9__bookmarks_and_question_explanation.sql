ALTER TABLE questions ADD COLUMN explanation TEXT;

CREATE TABLE public_question_bookmarks (
    actor_user_id UUID NOT NULL,
    publication_id UUID NOT NULL REFERENCES public_questions(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT timezone('UTC',CURRENT_TIMESTAMP),
    PRIMARY KEY (actor_user_id,publication_id)
);
CREATE INDEX idx_public_bookmarks_publication ON public_question_bookmarks(publication_id);
