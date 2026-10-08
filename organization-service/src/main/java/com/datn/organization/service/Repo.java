package com.datn.organization.service;

import com.datn.organization.domain.Model.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All SQL of the Organization schema. Mutations run under the organization row lock taken by {@link #lockOrg}. */
@Repository
public class Repo {
    private static final String ORG = "id,name,description,status,created_by,created_at,updated_at,archived_at,revision";
    private static final String MEM = "id,organization_id,user_id,role,status,can_review,joined_at,ended_at,ended_by,updated_at,revision";
    private static final String INV = "id,organization_id,recipient_user_id,role,invited_by,status,created_at,expires_at,responded_at,membership_id,revision";
    private final JdbcClient db;

    public Repo(JdbcClient db) { this.db = db; }

    // ---- organizations
    public void insertOrg(Org o) {
        db.sql("INSERT INTO organizations(id,name,description,status,created_by,created_at,updated_at,revision) VALUES (:id,:name,:d,:s,:by,:at,:at,1)")
                .param("id", o.id()).param("name", o.name()).param("d", o.description()).param("s", o.status()).param("by", o.createdBy())
                .param("at", o.createdAt()).update();
    }
    public Optional<Org> org(UUID id) {
        return db.sql("SELECT " + ORG + " FROM organizations WHERE id=:id").param("id", id).query(Org.class).optional();
    }
    public Optional<Org> lockOrg(UUID id) {
        return db.sql("SELECT " + ORG + " FROM organizations WHERE id=:id FOR UPDATE").param("id", id).query(Org.class).optional();
    }
    public void updateOrg(UUID id, String name, String description, OffsetDateTime at) {
        db.sql("UPDATE organizations SET name=:n,description=:d,updated_at=:at,revision=revision+1 WHERE id=:id")
                .param("n", name).param("d", description).param("at", at).param("id", id).update();
    }
    public void archiveOrg(UUID id, UUID by, OffsetDateTime at) {
        db.sql("UPDATE organizations SET status='ARCHIVED',archived_at=:at,archived_by=:by,updated_at=:at,revision=revision+1 WHERE id=:id")
                .param("at", at).param("by", by).param("id", id).update();
    }
    public void bumpOrg(UUID id, OffsetDateTime at) {
        db.sql("UPDATE organizations SET updated_at=:at,revision=revision+1 WHERE id=:id").param("at", at).param("id", id).update();
    }
    public long countMyOrgs(UUID userId, String status, String q) {
        return db.sql("SELECT count(*) FROM organizations o JOIN memberships m ON m.organization_id=o.id AND m.status='ACTIVE' AND m.user_id=:u "
                + "WHERE (:s::text IS NULL OR o.status=:s) AND (:q::text IS NULL OR lower(o.name) LIKE :q)")
                .param("u", userId).param("s", status).param("q", q).query(Long.class).single();
    }
    public record OrgWithMem(UUID orgId, String name, String description, String orgStatus, OffsetDateTime createdAt,
                             OffsetDateTime updatedAt, OffsetDateTime archivedAt, long orgRevision, UUID membershipId,
                             String role, boolean canReview) {}
    public List<OrgWithMem> myOrgs(UUID userId, String status, String q, int limit, long offset) {
        return db.sql("SELECT o.id AS org_id,o.name,o.description,o.status AS org_status,o.created_at,o.updated_at,o.archived_at,o.revision AS org_revision,"
                + "m.id AS membership_id,m.role,m.can_review FROM organizations o JOIN memberships m ON m.organization_id=o.id AND m.status='ACTIVE' AND m.user_id=:u "
                + "WHERE (:s::text IS NULL OR o.status=:s) AND (:q::text IS NULL OR lower(o.name) LIKE :q) ORDER BY o.created_at DESC,o.id LIMIT :l OFFSET :o")
                .param("u", userId).param("s", status).param("q", q).param("l", limit).param("o", offset).query(OrgWithMem.class).list();
    }

    // ---- memberships
    public void insertMem(Mem m) {
        db.sql("INSERT INTO memberships(id,organization_id,user_id,role,status,can_review,joined_at,updated_at,revision) VALUES (:id,:o,:u,:r,'ACTIVE',:c,:at,:at,1)")
                .param("id", m.id()).param("o", m.organizationId()).param("u", m.userId()).param("r", m.role()).param("c", m.canReview())
                .param("at", m.joinedAt()).update();
    }
    public Optional<Mem> activeMem(UUID orgId, UUID userId) {
        return db.sql("SELECT " + MEM + " FROM memberships WHERE organization_id=:o AND user_id=:u AND status='ACTIVE'")
                .param("o", orgId).param("u", userId).query(Mem.class).optional();
    }
    public Optional<Mem> mem(UUID orgId, UUID membershipId) {
        return db.sql("SELECT " + MEM + " FROM memberships WHERE organization_id=:o AND id=:id").param("o", orgId).param("id", membershipId)
                .query(Mem.class).optional();
    }
    public void changeMem(UUID id, String role, boolean canReview, OffsetDateTime at) {
        db.sql("UPDATE memberships SET role=:r,can_review=:c,updated_at=:at,revision=revision+1 WHERE id=:id")
                .param("r", role).param("c", canReview).param("at", at).param("id", id).update();
    }
    public void endMem(UUID id, String status, UUID by, OffsetDateTime at) {
        db.sql("UPDATE memberships SET status=:s,ended_at=:at,ended_by=:by,updated_at=:at,revision=revision+1 WHERE id=:id AND status='ACTIVE'")
                .param("s", status).param("by", by).param("at", at).param("id", id).update();
    }
    public long countActiveOwners(UUID orgId) {
        return db.sql("SELECT count(*) FROM memberships WHERE organization_id=:o AND role='OWNER' AND status='ACTIVE'").param("o", orgId).query(Long.class).single();
    }
    public long countActive(UUID orgId) {
        return db.sql("SELECT count(*) FROM memberships WHERE organization_id=:o AND status='ACTIVE'").param("o", orgId).query(Long.class).single();
    }
    public long countMems(UUID orgId, String status, String role) {
        return db.sql("SELECT count(*) FROM memberships WHERE organization_id=:o AND (:s::text IS NULL OR status=:s) AND (:r::text IS NULL OR role=:r)")
                .param("o", orgId).param("s", status).param("r", role).query(Long.class).single();
    }
    public List<Mem> mems(UUID orgId, String status, String role, int limit, long offset) {
        return db.sql("SELECT " + MEM + " FROM memberships WHERE organization_id=:o AND (:s::text IS NULL OR status=:s) AND (:r::text IS NULL OR role=:r) "
                + "ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,joined_at,id LIMIT :l OFFSET :off")
                .param("o", orgId).param("s", status).param("r", role).param("l", limit).param("off", offset).query(Mem.class).list();
    }

    // ---- invitations
    public void insertInv(Inv i) {
        db.sql("INSERT INTO invitations(id,organization_id,recipient_user_id,role,invited_by,status,created_at,expires_at,revision) VALUES (:id,:o,:u,:r,:by,'PENDING',:at,:ex,1)")
                .param("id", i.id()).param("o", i.organizationId()).param("u", i.recipientUserId()).param("r", i.role()).param("by", i.invitedBy())
                .param("at", i.createdAt()).param("ex", i.expiresAt()).update();
    }
    public Optional<Inv> inv(UUID id) {
        return db.sql("SELECT " + INV + " FROM invitations WHERE id=:id").param("id", id).query(Inv.class).optional();
    }
    public Optional<Inv> pendingInv(UUID orgId, UUID recipient) {
        return db.sql("SELECT " + INV + " FROM invitations WHERE organization_id=:o AND recipient_user_id=:u AND status='PENDING'")
                .param("o", orgId).param("u", recipient).query(Inv.class).optional();
    }
    /** Conditional transition; false when the invitation is no longer PENDING. */
    public boolean closeInv(UUID id, String status, UUID membershipId, OffsetDateTime at) {
        return db.sql("UPDATE invitations SET status=:s,responded_at=:at,membership_id=:m,revision=revision+1 WHERE id=:id AND status='PENDING'")
                .param("s", status).param("at", at).param("m", membershipId).param("id", id).update() == 1;
    }
    public long countInvs(UUID orgId, String status) {
        return db.sql("SELECT count(*) FROM invitations WHERE organization_id=:o AND (:s::text IS NULL OR status=:s)").param("o", orgId).param("s", status).query(Long.class).single();
    }
    public List<Inv> invs(UUID orgId, String status, int limit, long offset) {
        return db.sql("SELECT " + INV + " FROM invitations WHERE organization_id=:o AND (:s::text IS NULL OR status=:s) ORDER BY created_at DESC,id LIMIT :l OFFSET :off")
                .param("o", orgId).param("s", status).param("l", limit).param("off", offset).query(Inv.class).list();
    }
    public long countMyInvs(UUID userId, String status) {
        return db.sql("SELECT count(*) FROM invitations WHERE recipient_user_id=:u AND (:s::text IS NULL OR status=:s)").param("u", userId).param("s", status).query(Long.class).single();
    }
    public List<Inv> myInvs(UUID userId, String status, int limit, long offset) {
        return db.sql("SELECT " + INV + " FROM invitations WHERE recipient_user_id=:u AND (:s::text IS NULL OR status=:s) ORDER BY created_at DESC,id LIMIT :l OFFSET :off")
                .param("u", userId).param("s", status).param("l", limit).param("off", offset).query(Inv.class).list();
    }
    public List<Inv> lockLapsed(OffsetDateTime now, int limit) {
        return db.sql("SELECT " + INV + " FROM invitations WHERE status='PENDING' AND expires_at<=:now ORDER BY expires_at LIMIT :l FOR UPDATE SKIP LOCKED")
                .param("now", now).param("l", limit).query(Inv.class).list();
    }
    public String orgName(UUID id) {
        return db.sql("SELECT name FROM organizations WHERE id=:id").param("id", id).query(String.class).optional().orElse(null);
    }

    // ---- audit
    public void audit(UUID orgId, UUID actor, String action, UUID target, UUID membershipId, UUID invitationId, String detail, OffsetDateTime at) {
        db.sql("INSERT INTO audit_events(id,organization_id,actor_user_id,action,target_user_id,membership_id,invitation_id,detail,occurred_at) VALUES (:id,:o,:a,:ac,:t,:m,:i,:d,:at)")
                .param("id", UUID.randomUUID()).param("o", orgId).param("a", actor).param("ac", action).param("t", target).param("m", membershipId)
                .param("i", invitationId).param("d", detail).param("at", at).update();
    }
    public long countAudit(UUID orgId) {
        return db.sql("SELECT count(*) FROM audit_events WHERE organization_id=:o").param("o", orgId).query(Long.class).single();
    }
    public List<Audit> audits(UUID orgId, int limit, long offset) {
        return db.sql("SELECT id,organization_id,actor_user_id,action,target_user_id,membership_id,invitation_id,detail,occurred_at FROM audit_events "
                + "WHERE organization_id=:o ORDER BY occurred_at DESC,id LIMIT :l OFFSET :off").param("o", orgId).param("l", limit).param("off", offset).query(Audit.class).list();
    }

    // ---- internal authorization
    public record Decision(String orgName, String orgStatus, UUID membershipId, String role, boolean canReview) {}
    public Optional<Decision> decision(UUID orgId, UUID userId) {
        return db.sql("SELECT o.name AS org_name,o.status AS org_status,m.id AS membership_id,m.role,COALESCE(m.can_review,FALSE) AS can_review "
                + "FROM organizations o LEFT JOIN memberships m ON m.organization_id=o.id AND m.user_id=:u AND m.status='ACTIVE' WHERE o.id=:o")
                .param("o", orgId).param("u", userId).query(Decision.class).optional();
    }
    public record Discovered(UUID orgId, String orgName, String orgStatus, UUID membershipId, String role, boolean canReview) {}
    public List<Discovered> discover(UUID userId, int limit) {
        return db.sql("SELECT o.id AS org_id,o.name AS org_name,o.status AS org_status,m.id AS membership_id,m.role,m.can_review FROM memberships m "
                + "JOIN organizations o ON o.id=m.organization_id WHERE m.user_id=:u AND m.status='ACTIVE' ORDER BY o.name,o.id LIMIT :l")
                .param("u", userId).param("l", limit).query(Discovered.class).list();
    }
}
