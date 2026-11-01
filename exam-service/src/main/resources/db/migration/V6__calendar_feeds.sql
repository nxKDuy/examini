-- Personal calendar subscription (iCalendar feed). Only a SHA-256 hash of the secret token is stored;
-- the feed is read-only and lists the same exams the user sees on the in-app calendar.
CREATE TABLE IF NOT EXISTS calendar_feeds (
    user_id      UUID PRIMARY KEY,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    actor_role   VARCHAR(16) NOT NULL CHECK (actor_role IN ('STUDENT', 'TEACHER', 'ADMIN')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ
);
