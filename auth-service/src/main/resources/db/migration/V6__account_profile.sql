-- Personal profile, password state, account lock and last sign-in.
ALTER TABLE users ADD COLUMN phone VARCHAR(30);
ALTER TABLE users ADD COLUMN school VARCHAR(255);
ALTER TABLE users ADD COLUMN bio VARCHAR(1000);
ALTER TABLE users ADD COLUMN password_set BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN locked_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;

-- Avatars live apart from users so that loading a user never loads the image.
CREATE TABLE user_avatars (
    user_id       UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    content       BYTEA        NOT NULL,
    content_type  VARCHAR(30)  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- Sign-in compares lower-cased emails; registration used to store them as typed.
UPDATE users u SET email = lower(trim(u.email))
WHERE u.email <> lower(trim(u.email))
  AND NOT EXISTS (SELECT 1 FROM users o WHERE o.id <> u.id AND lower(trim(o.email)) = lower(trim(u.email)));
