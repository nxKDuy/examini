CREATE TABLE question_copy_sync_cursors (
    question_id UUID PRIMARY KEY REFERENCES questions(id) ON DELETE CASCADE,
    reference_snapshot TEXT NOT NULL,
    last_source_version BIGINT NOT NULL,
    cursor_revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE question_set_copy_sync_cursors (
    question_set_id UUID PRIMARY KEY REFERENCES question_sets(id) ON DELETE CASCADE,
    source_publication_id UUID NOT NULL REFERENCES public_questions(id),
    reference_snapshot TEXT NOT NULL,
    last_source_set_version BIGINT NOT NULL,
    cursor_revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE copy_sync_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_kind VARCHAR(20) NOT NULL CHECK (resource_kind IN ('QUESTION','QUESTION_SET')),
    resource_id UUID NOT NULL,
    local_version BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    actions TEXT NOT NULL,
    actor_user_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_copy_sync_events_resource ON copy_sync_events(resource_kind,resource_id,created_at DESC);
