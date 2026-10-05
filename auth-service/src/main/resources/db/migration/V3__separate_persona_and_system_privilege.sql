ALTER TABLE users
    ADD COLUMN personal_persona VARCHAR(20),
    ADD COLUMN system_privilege VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT NOW();

UPDATE users
SET personal_persona = CASE role
        WHEN 'STUDENT' THEN 'STUDENT'
        WHEN 'TEACHER' THEN 'TEACHER'
        ELSE NULL
    END,
    system_privilege = CASE role
        WHEN 'ADMIN' THEN 'ADMIN'
        ELSE 'NONE'
    END;

ALTER TABLE users
    ADD CONSTRAINT chk_users_personal_persona
        CHECK (personal_persona IS NULL OR personal_persona IN ('STUDENT', 'TEACHER')),
    ADD CONSTRAINT chk_users_system_privilege
        CHECK (system_privilege IN ('NONE', 'ADMIN'));
