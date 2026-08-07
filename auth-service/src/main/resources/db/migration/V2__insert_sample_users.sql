-- V2: Insert sample accounts for TEACHER and STUDENT roles
-- Password for both accounts is: Admin@123 (same hash as admin)

INSERT INTO users (email, password_hash, full_name, role)
VALUES ('teacher@datn.local',
        '$2a$10$h3tD/x47lL/rAOgWU9mLcOqBn0XUhvsxwJCUtYU12K4YCm3izuFKq',
        'Giáo viên Test', 'TEACHER')
ON CONFLICT (email) DO NOTHING;

INSERT INTO users (email, password_hash, full_name, role)
VALUES ('student@datn.local',
        '$2a$10$h3tD/x47lL/rAOgWU9mLcOqBn0XUhvsxwJCUtYU12K4YCm3izuFKq',
        'Học sinh Test', 'STUDENT')
ON CONFLICT (email) DO NOTHING;
