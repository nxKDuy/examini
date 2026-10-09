ALTER TABLE exam_results ADD COLUMN student_display_name VARCHAR(255);
UPDATE exam_results SET student_display_name=CAST(user_id AS VARCHAR) WHERE student_display_name IS NULL;

ALTER TABLE question_grades ADD COLUMN explanation TEXT;
