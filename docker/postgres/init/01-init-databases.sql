-- Tạo các database riêng cho từng service (database-per-service pattern)
-- Script này chạy tự động khi Postgres container khởi động lần đầu


CREATE DATABASE question_bank_db;
CREATE DATABASE exam_db;
CREATE DATABASE exam_session_db;

\c auth_db;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

\c question_bank_db;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

\c exam_db;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

\c exam_session_db;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE DATABASE grading_db;
CREATE DATABASE analytics_db;
