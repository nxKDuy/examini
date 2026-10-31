-- Practice exams: students may check an answer right away. The session remembers it is a practice attempt; a checked
-- answer keeps the verdict shown to the student and can no longer be changed.
ALTER TABLE exam_sessions ADD COLUMN practice BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE session_answers ADD COLUMN checked_at TIMESTAMP;
ALTER TABLE session_answers ADD COLUMN check_result TEXT;
