-- AI question generation reuses the staged import review: a generation request is a job of kind AI,
-- its questions are items with source method AI that always require teacher review before import.
ALTER TABLE question_import_jobs DROP CONSTRAINT question_import_jobs_file_kind_check;
ALTER TABLE question_import_jobs ADD CONSTRAINT question_import_jobs_file_kind_check
    CHECK (file_kind IN ('DOCX','PDF','IMAGE','AI'));

ALTER TABLE question_import_items DROP CONSTRAINT question_import_items_source_method_check;
ALTER TABLE question_import_items ADD CONSTRAINT question_import_items_source_method_check
    CHECK (source_method IN ('TEXT','OCR','AI'));

-- Generation parameters and provider/model (JSON), kept as provenance; NULL for document jobs.
ALTER TABLE question_import_jobs ADD COLUMN generation TEXT;
