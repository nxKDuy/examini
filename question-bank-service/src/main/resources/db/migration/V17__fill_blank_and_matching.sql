-- Two structured objective types:
--  FILL_BLANK: the stem marks each gap with ___ (three or more underscores); option i (display order) holds the accepted
--              answers of gap i, alternatives separated by |.
--  MATCHING:   each option is one pair "left => right"; students match every left item with one of the right items.
ALTER TABLE questions DROP CONSTRAINT questions_question_type_check;
ALTER TABLE questions ADD CONSTRAINT questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING','MULTIPLE_RESPONSE','TRUE_FALSE_GROUP','FILL_BLANK','MATCHING'));
ALTER TABLE public_questions DROP CONSTRAINT public_questions_question_type_check;
ALTER TABLE public_questions ADD CONSTRAINT public_questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING','MULTIPLE_RESPONSE','TRUE_FALSE_GROUP','FILL_BLANK','MATCHING'));
ALTER TABLE organization_questions DROP CONSTRAINT organization_questions_question_type_check;
ALTER TABLE organization_questions ADD CONSTRAINT organization_questions_question_type_check
    CHECK (question_type IN ('MULTIPLE_CHOICE','TRUE_FALSE','SHORT_ANSWER','ESSAY','SPEAKING','MULTIPLE_RESPONSE','TRUE_FALSE_GROUP','FILL_BLANK','MATCHING'));
