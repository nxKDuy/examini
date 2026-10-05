-- Preserve user IDs and owned resources while making the legacy shared seed unusable.
UPDATE users
SET password_hash = '!disabled-legacy-seed!'
WHERE password_hash = '$2a$10$h3tD/x47lL/rAOgWU9mLcOqBn0XUhvsxwJCUtYU12K4YCm3izuFKq';
