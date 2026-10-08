-- Organization-owned schema. user_id columns are Auth UUID references only (no FK / no foreign table access).
CREATE TABLE organizations (
    id             UUID PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    description    VARCHAR(1000),
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_by     UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    archived_at    TIMESTAMPTZ,
    archived_by    UUID,
    revision       BIGINT       NOT NULL DEFAULT 1
);

CREATE TABLE memberships (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organizations(id),
    user_id         UUID        NOT NULL,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','REVOKED','LEFT')),
    can_review      BOOLEAN     NOT NULL DEFAULT FALSE,
    joined_at       TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    ended_by        UUID,
    updated_at      TIMESTAMPTZ NOT NULL,
    revision        BIGINT      NOT NULL DEFAULT 1,
    CHECK (can_review = FALSE OR role = 'MEMBER')
);
-- One ACTIVE membership per user and organization; history rows (REVOKED/LEFT) are kept.
CREATE UNIQUE INDEX ux_membership_active ON memberships (organization_id, user_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_membership_user_active ON memberships (user_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_membership_org ON memberships (organization_id, status);

CREATE TABLE invitations (
    id                 UUID PRIMARY KEY,
    organization_id    UUID        NOT NULL REFERENCES organizations(id),
    recipient_user_id  UUID        NOT NULL,
    role               VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','MEMBER')),
    invited_by         UUID        NOT NULL,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','DECLINED','REVOKED','EXPIRED')),
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    responded_at       TIMESTAMPTZ,
    membership_id      UUID,
    revision           BIGINT      NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX ux_invitation_pending ON invitations (organization_id, recipient_user_id) WHERE status = 'PENDING';
CREATE INDEX ix_invitation_recipient ON invitations (recipient_user_id, status);
CREATE INDEX ix_invitation_org ON invitations (organization_id, status);

CREATE TABLE audit_events (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    actor_user_id   UUID         NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    target_user_id  UUID,
    membership_id   UUID,
    invitation_id   UUID,
    detail          VARCHAR(500),
    occurred_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_audit_org ON audit_events (organization_id, occurred_at DESC);

CREATE TABLE outbox_events (
    event_id    UUID PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    payload     TEXT         NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    delivered   BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_outbox_pending ON outbox_events (occurred_at) WHERE delivered = FALSE;
