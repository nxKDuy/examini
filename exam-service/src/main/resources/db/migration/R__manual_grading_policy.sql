DO $$
DECLARE policy_column text;
BEGIN
  FOREACH policy_column IN ARRAY ARRAY[
    'result_auto_release','result_show_score','result_show_question_breakdown','result_show_feedback',
    'result_show_correct_answers','result_show_explanation'
  ] LOOP
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema=current_schema() AND c.table_name='exams' AND c.column_name=policy_column) THEN
      EXECUTE format('ALTER TABLE exams ADD COLUMN %I BOOLEAN NOT NULL DEFAULT %L',policy_column,
        CASE WHEN policy_column IN ('result_show_correct_answers','result_show_explanation') THEN false ELSE true END);
    END IF;
  END LOOP;
END $$;
