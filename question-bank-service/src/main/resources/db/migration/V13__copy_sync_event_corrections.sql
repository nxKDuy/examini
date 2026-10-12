-- Correcting audit for copy sync history. Events stay append-only: a wrong value is
-- never rewritten; a correction row records recorded vs corrected value and why.
CREATE TABLE copy_sync_event_corrections (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID         NOT NULL REFERENCES copy_sync_events(id),
    field           VARCHAR(30)  NOT NULL CHECK (field IN ('LOCAL_VERSION')),
    recorded_value  BIGINT       NOT NULL,
    corrected_value BIGINT       NOT NULL,
    reason          VARCHAR(1000) NOT NULL,
    corrected_by    VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (event_id, field)
);

-- Before bd974de a Question apply that took a source value recorded the new DRAFT version + 1.
-- Such an event is recognisable from the revisions: version (recorded - 1) was saved by the same
-- actor in the same apply (within seconds), and no revision with the recorded number existed then.
INSERT INTO copy_sync_event_corrections(event_id, field, recorded_value, corrected_value, reason, corrected_by)
SELECT e.id, 'LOCAL_VERSION', e.local_version, e.local_version - 1,
       'Lỗi ghi lịch sử trước bd974de: phiên bản local bị cộng thêm 1; phiên bản thực tế do lần áp dụng này tạo ra là '
           || (e.local_version - 1),
       'MIGRATION_V13'
FROM copy_sync_events e
WHERE e.resource_kind = 'QUESTION'
  AND e.actions LIKE '%TAKE_SOURCE%'
  AND EXISTS (SELECT 1 FROM question_versions v
              WHERE v.question_id = e.resource_id AND v.version_number = e.local_version - 1
                AND v.changed_by = e.actor_user_id
                AND v.created_at BETWEEN e.created_at - INTERVAL '5 seconds' AND e.created_at + INTERVAL '5 seconds')
  AND NOT EXISTS (SELECT 1 FROM question_versions v
                  WHERE v.question_id = e.resource_id AND v.version_number = e.local_version
                    AND v.created_at <= e.created_at + INTERVAL '5 seconds');
