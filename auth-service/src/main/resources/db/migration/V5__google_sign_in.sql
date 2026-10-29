-- Google Sign-In: the stable Google account id, linked on first Google sign-in (verified email) or at sign-up.
ALTER TABLE users ADD COLUMN google_subject VARCHAR(255);
ALTER TABLE users ADD CONSTRAINT uq_users_google_subject UNIQUE (google_subject);
