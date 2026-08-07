-- V1: Khởi tạo bảng users cho auth-service
-- Flyway tự động chạy khi service khởi động

CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'TEACHER', 'STUDENT')),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Index tăng tốc lookup theo email (đã có unique constraint nhưng explicit index rõ ràng hơn)
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Tài khoản admin mặc định (đổi password ngay sau deploy)
-- Password: Admin@123 (BCrypt)
INSERT INTO users (email, password_hash, full_name, role)
VALUES ('admin@datn.local',
        '$2a$10$h3tD/x47lL/rAOgWU9mLcOqBn0XUhvsxwJCUtYU12K4YCm3izuFKq',
        'Administrator', 'ADMIN')
ON CONFLICT (email) DO NOTHING;
