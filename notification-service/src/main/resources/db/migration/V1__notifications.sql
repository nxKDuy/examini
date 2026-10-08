-- V1: in-app notification inbox. Source resources are referenced by ID only.
CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    recipient_user_id UUID          NOT NULL,
    type              VARCHAR(64)   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    message           VARCHAR(1000) NOT NULL,
    entity_type       VARCHAR(32),
    entity_id         UUID,
    metadata          JSONB         NOT NULL DEFAULT '{}',
    source_event_id   UUID          NOT NULL,
    source_event_type VARCHAR(64)   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    read_at           TIMESTAMPTZ
);
-- One logical notification per source event and recipient: broker redelivery is a no-op.
CREATE UNIQUE INDEX notification_dedup ON notifications(source_event_id, recipient_user_id);
CREATE INDEX notification_inbox ON notifications(recipient_user_id, created_at DESC, id DESC);
CREATE INDEX notification_unread ON notifications(recipient_user_id, created_at DESC) WHERE read_at IS NULL;
