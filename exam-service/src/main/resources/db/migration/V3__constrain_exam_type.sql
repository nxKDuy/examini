ALTER TABLE exams
    ADD CONSTRAINT exams_type_check CHECK (type IN ('PRACTICE', 'EXAM'));
