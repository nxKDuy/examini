ALTER TABLE exams DROP CONSTRAINT exams_status_check;
ALTER TABLE exams ADD CONSTRAINT exams_status_check CHECK(status IN ('DRAFT','PUBLISHED','OPEN','CLOSED','COMPLETED'));
ALTER TABLE exams ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'EXAM',
 ADD COLUMN passing_score REAL, ADD COLUMN shuffle_questions BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN shuffle_options BOOLEAN NOT NULL DEFAULT false, ADD COLUMN require_cam BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN require_mic BOOLEAN NOT NULL DEFAULT false, ADD COLUMN block_tab_switch BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN require_fullscreen BOOLEAN NOT NULL DEFAULT false,
 ADD COLUMN access_mode VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED' CHECK(access_mode IN('ASSIGNED','JOIN_CODE','PUBLIC')),
 ADD COLUMN max_participants INT NOT NULL DEFAULT 100 CHECK(max_participants>0),
 ADD COLUMN max_concurrent_sessions INT NOT NULL DEFAULT 50 CHECK(max_concurrent_sessions>0),
 ADD COLUMN attempt_limit INT NOT NULL DEFAULT 1 CHECK(attempt_limit>0),
 ADD COLUMN snapshots JSONB NOT NULL DEFAULT '[]';
CREATE TABLE exam_members(exam_id UUID NOT NULL REFERENCES exams(id),user_id UUID NOT NULL,role VARCHAR(20) NOT NULL CHECK(role IN ('MANAGER','PROCTOR')),PRIMARY KEY(exam_id,user_id));
CREATE TABLE exam_invitations(exam_id UUID NOT NULL REFERENCES exams(id),user_id UUID NOT NULL,role VARCHAR(20) NOT NULL,PRIMARY KEY(exam_id,user_id));
CREATE TABLE exam_participants(exam_id UUID NOT NULL REFERENCES exams(id),user_id UUID NOT NULL,source VARCHAR(30) NOT NULL,PRIMARY KEY(exam_id,user_id));
CREATE TABLE exam_question_points(exam_id UUID NOT NULL REFERENCES exams(id),question_id UUID NOT NULL,points NUMERIC(12,4) NOT NULL CHECK(points>0),PRIMARY KEY(exam_id,question_id));
INSERT INTO exam_question_points SELECT DISTINCT exam_id,question_id,1 FROM exam_questions;
CREATE TABLE exam_audit(id UUID PRIMARY KEY,exam_id UUID NOT NULL,actor_user_id UUID NOT NULL,action VARCHAR(255) NOT NULL,timestamp TIMESTAMPTZ NOT NULL,metadata TEXT);

ALTER TABLE exam_questions ADD COLUMN position INT;
WITH ranked AS(SELECT ctid,row_number() OVER(PARTITION BY exam_id ORDER BY ctid)-1 AS n FROM exam_questions)
UPDATE exam_questions q SET position=r.n FROM ranked r WHERE q.ctid=r.ctid;
