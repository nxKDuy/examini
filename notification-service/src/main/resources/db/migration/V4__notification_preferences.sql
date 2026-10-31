-- V4: per-user channel preferences. Additive. A missing row means "on" for both channels, so every existing user keeps
-- today's behaviour. The in-app inbox is never switched off: preferences only gate email and browser push.
CREATE TABLE notification_preferences (
    user_id    UUID        NOT NULL,
    category   VARCHAR(16) NOT NULL CHECK (category IN ('SCHEDULE', 'REMINDER', 'EXAM_STATUS', 'RESULT', 'GRADING')),
    email      BOOLEAN     NOT NULL DEFAULT TRUE,
    push       BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, category)
);
