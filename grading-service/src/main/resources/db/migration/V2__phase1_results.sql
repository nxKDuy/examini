ALTER TABLE exam_results DROP CONSTRAINT exam_results_status_check;
ALTER TABLE exam_results ADD CONSTRAINT exam_results_status_check CHECK(status IN('PENDING','NEEDS_REVIEW','GRADED'));
ALTER TABLE exam_results ADD COLUMN max_score REAL,ADD COLUMN exam_title VARCHAR(255),ADD COLUMN feedback TEXT,ADD COLUMN submitted_at TIMESTAMP,ADD COLUMN duration_seconds INT,ADD COLUMN graded_by UUID;
ALTER TABLE question_grades ADD COLUMN question_content TEXT,ADD COLUMN question_type VARCHAR(255),ADD COLUMN point_value REAL,ADD COLUMN answer_content TEXT,ADD COLUMN selected_option_content TEXT,ADD COLUMN needs_review BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX one_question_grade ON question_grades(result_id,question_id);
