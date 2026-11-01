-- Question presentation (images, audio, reading passage, limits) frozen with the grade, so teachers and students see
-- the question as it was asked.
ALTER TABLE question_grades ADD COLUMN presentation JSONB CHECK (presentation IS NULL OR jsonb_typeof(presentation) = 'object');
