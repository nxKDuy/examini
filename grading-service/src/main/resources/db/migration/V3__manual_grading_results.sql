ALTER TABLE exam_results ALTER COLUMN graded_at DROP DEFAULT;
ALTER TABLE exam_results ALTER COLUMN graded_at DROP NOT NULL;
ALTER TABLE exam_results ALTER COLUMN graded_at TYPE TIMESTAMPTZ USING graded_at AT TIME ZONE 'UTC';
ALTER TABLE exam_results ALTER COLUMN submitted_at TYPE TIMESTAMPTZ USING submitted_at AT TIME ZONE 'UTC';
ALTER TABLE exam_results ADD COLUMN result_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE exam_results ADD COLUMN released_revision BIGINT;
ALTER TABLE exam_results ADD COLUMN pending_public_reason TEXT;
ALTER TABLE exam_results ADD COLUMN pending_private_audit_note TEXT;
ALTER TABLE exam_results ADD COLUMN grading_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';
ALTER TABLE exam_results ADD CONSTRAINT ck_exam_result_revision CHECK(result_revision > 0 AND (released_revision IS NULL OR released_revision > 0));
ALTER TABLE exam_results ADD CONSTRAINT ck_exam_result_mode CHECK(grading_mode IN('AUTO','MANUAL'));

ALTER TABLE question_grades ALTER COLUMN score DROP NOT NULL;
ALTER TABLE question_grades ADD COLUMN auto_score REAL;
ALTER TABLE question_grades ADD COLUMN manual_score REAL;
ALTER TABLE question_grades ADD COLUMN question_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE question_grades ADD COLUMN position INT NOT NULL DEFAULT 0;
ALTER TABLE question_grades ADD COLUMN manual_graded_by UUID;
ALTER TABLE question_grades ADD COLUMN manual_graded_at TIMESTAMPTZ;
UPDATE question_grades SET auto_score=score WHERE needs_review=false;
WITH ordered AS (SELECT id,row_number() OVER(PARTITION BY result_id ORDER BY question_id)-1 AS pos FROM question_grades)
UPDATE question_grades q SET position=ordered.pos FROM ordered WHERE ordered.id=q.id;
ALTER TABLE question_grades ADD CONSTRAINT ck_question_grade_scores CHECK(
  point_value IS NULL OR point_value >= 0 AND
  (score IS NULL OR score >= 0 AND score <= point_value) AND
  (auto_score IS NULL OR auto_score >= 0 AND auto_score <= point_value) AND
  (manual_score IS NULL OR manual_score >= 0 AND manual_score <= point_value));

CREATE TABLE result_publications(
  id UUID PRIMARY KEY,
  result_id UUID NOT NULL REFERENCES exam_results(id) ON DELETE CASCADE,
  result_revision BIGINT NOT NULL,
  total_score REAL NOT NULL,
  feedback TEXT,
  public_reason TEXT,
  grading_mode VARCHAR(16) NOT NULL CHECK(grading_mode IN('AUTO','MANUAL')),
  released_at TIMESTAMPTZ NOT NULL,
  items JSONB NOT NULL,
  CONSTRAINT uq_result_publication_revision UNIQUE(result_id,result_revision)
);
CREATE INDEX idx_result_publications_result ON result_publications(result_id,result_revision);

INSERT INTO result_publications(id,result_id,result_revision,total_score,feedback,public_reason,grading_mode,released_at,items)
SELECT gen_random_uuid(),r.id,1,r.total_score,r.feedback,NULL,'AUTO',COALESCE(r.graded_at,now()),
       COALESCE(jsonb_agg(jsonb_build_object('questionId',q.question_id,'score',q.score,'feedback',q.feedback) ORDER BY q.position)
                FILTER(WHERE q.id IS NOT NULL),'[]'::jsonb)
FROM exam_results r LEFT JOIN question_grades q ON q.result_id=r.id
WHERE r.status='GRADED' AND r.total_score IS NOT NULL
GROUP BY r.id;
UPDATE exam_results SET released_revision=1 WHERE status='GRADED' AND total_score IS NOT NULL;

CREATE TABLE grading_audit(
  id UUID PRIMARY KEY,
  result_id UUID NOT NULL REFERENCES exam_results(id) ON DELETE CASCADE,
  actor_user_id UUID,
  action VARCHAR(40) NOT NULL,
  result_revision BIGINT NOT NULL,
  public_reason TEXT,
  private_note TEXT,
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_grading_audit_result ON grading_audit(result_id,created_at);

CREATE TABLE grading_event_outbox(
  event_id UUID PRIMARY KEY,
  result_id UUID NOT NULL REFERENCES exam_results(id) ON DELETE CASCADE,
  result_revision BIGINT NOT NULL,
  exchange_name VARCHAR(80) NOT NULL,
  routing_key VARCHAR(80) NOT NULL,
  payload JSONB NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  delivered BOOLEAN NOT NULL DEFAULT false,
  attempt_count INT NOT NULL DEFAULT 0,
  CONSTRAINT uq_grading_event_revision_route UNIQUE(result_id,result_revision,routing_key)
);
CREATE INDEX idx_grading_outbox_pending ON grading_event_outbox(delivered,occurred_at);
