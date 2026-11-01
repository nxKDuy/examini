-- Two objective types used in Vietnamese exams since 2025:
--  MULTIPLE_RESPONSE: several options may be correct, the student ticks all of them;
--  TRUE_FALSE_GROUP: one stem with 2-8 statements (a, b, c, d), each judged Đúng/Sai (options.is_correct = the statement is true).
ALTER TABLE questions DROP CONSTRAINT questions_question_type_check;
ALTER TABLE questions ADD CONSTRAINT questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING','MULTIPLE_RESPONSE','TRUE_FALSE_GROUP'));
ALTER TABLE public_questions DROP CONSTRAINT public_questions_question_type_check;
ALTER TABLE public_questions ADD CONSTRAINT public_questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING','MULTIPLE_RESPONSE','TRUE_FALSE_GROUP'));
ALTER TABLE organization_questions DROP CONSTRAINT organization_questions_question_type_check;
ALTER TABLE organization_questions ADD CONSTRAINT organization_questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING','MULTIPLE_RESPONSE','TRUE_FALSE_GROUP'));
