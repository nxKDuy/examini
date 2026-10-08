-- Volume cũ không chạy lại init scripts. Chỉ tạo DB còn thiếu; giữ nguyên dữ liệu.
SELECT 'CREATE DATABASE notification_db'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'notification_db')
\gexec
