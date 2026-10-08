package com.datn.organization.service;

import com.datn.organization.client.IdentityResolver;
import com.datn.organization.domain.Dto.*;
import com.datn.organization.domain.Model;
import com.datn.organization.domain.Model.*;
import com.datn.organization.events.Outbox;
import com.datn.organization.security.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Organization lifecycle, membership and invitation rules. Every mutation takes the organization row lock first, which
 * serializes last-OWNER, duplicate-invitation and membership races; unique indexes are the second line of defence.
 */
@Service
public class OrganizationService {
    private final Repo repo;
    private final IdentityResolver identities;
    private final Outbox outbox;
    private final int invitationTtlDays;

    public OrganizationService(Repo repo, IdentityResolver identities, Outbox outbox,
                               @Value("${organization.invitation-ttl-days:7}") int invitationTtlDays) {
        this.repo = repo; this.identities = identities; this.outbox = outbox; this.invitationTtlDays = invitationTtlDays;
    }

    static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    static ResponseStatusException error(HttpStatus status, String detail) { return new ResponseStatusException(status, detail); }

    // ================= organizations =================

    @Transactional
    public OrganizationView create(Actor actor, CreateOrganization req) {
        if (!actor.teacher()) throw error(HttpStatus.FORBIDDEN, "Chỉ giáo viên được tạo tổ chức");
        OffsetDateTime at = now();
        UUID id = UUID.randomUUID();
        repo.insertOrg(new Org(id, req.name().trim(), blankToNull(req.description()), Model.ACTIVE, actor.userId(), at, at, null, 1));
        Mem owner = new Mem(UUID.randomUUID(), id, actor.userId(), "OWNER", Model.ACTIVE, false, at, null, null, at, 1);
        repo.insertMem(owner);
        repo.audit(id, actor.userId(), "ORGANIZATION_CREATED", actor.userId(), owner.id(), null, null, at);
        return view(repo.org(id).orElseThrow(), owner, null);
    }

    @Transactional(readOnly = true)
    public PageView<OrganizationView> mine(Actor actor, String status, String q, Paging p) {
        String s = validOrgStatus(status);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long total = repo.countMyOrgs(actor.userId(), s, like);
        List<OrganizationView> items = repo.myOrgs(actor.userId(), s, like, p.size(), p.offset()).stream().map(o -> new OrganizationView(
                o.orgId(), o.name(), o.description(), o.orgStatus(), o.orgRevision(), o.createdAt(), o.updatedAt(), o.archivedAt(),
                access(o.membershipId(), o.role(), o.canReview(), o.orgStatus()), null)).toList();
        return p.page(items, total);
    }

    @Transactional(readOnly = true)
    public OrganizationView get(Actor actor, UUID orgId) {
        Mem me = member(orgId, actor);
        return view(repo.org(orgId).orElseThrow(), me, repo.countActive(orgId));
    }

    @Transactional(readOnly = true)
    public Access capabilities(Actor actor, UUID orgId) {
        Mem me = member(orgId, actor);
        return access(me, repo.org(orgId).orElseThrow().status());
    }

    @Transactional
    public OrganizationView update(Actor actor, UUID orgId, UpdateOrganization req) {
        if (req.name() == null && req.description() == null) throw error(HttpStatus.BAD_REQUEST, "Không có thay đổi");
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireRole(me, "OWNER");
        requireActive(org);
        checkRevision(org.revision(), req.revision());
        String name = req.name() == null ? org.name() : req.name().trim();
        if (name.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "Tên tổ chức không được để trống");
        String description = req.description() == null ? org.description() : blankToNull(req.description());
        OffsetDateTime at = now();
        repo.updateOrg(orgId, name, description, at);
        repo.audit(orgId, actor.userId(), "ORGANIZATION_UPDATED", null, me.id(), null, null, at);
        return view(repo.org(orgId).orElseThrow(), me, repo.countActive(orgId));
    }

    @Transactional
    public OrganizationView archive(Actor actor, UUID orgId, Revision req) {
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireRole(me, "OWNER");
        requireActive(org);
        checkRevision(org.revision(), req.revision());
        OffsetDateTime at = now();
        repo.archiveOrg(orgId, actor.userId(), at);
        repo.audit(orgId, actor.userId(), "ORGANIZATION_ARCHIVED", null, me.id(), null, null, at);
        return view(repo.org(orgId).orElseThrow(), me, repo.countActive(orgId));
    }

    // ================= members =================

    @Transactional(readOnly = true)
    public PageView<MemberView> members(Actor actor, UUID orgId, String status, String role, Paging p) {
        Mem me = member(orgId, actor);
        Org org = repo.org(orgId).orElseThrow();
        boolean manager = !"MEMBER".equals(me.role());
        // Plain members see the active roster only; history (REVOKED/LEFT) is for OWNER/ADMIN.
        String s = manager ? validMemStatus(status) : Model.ACTIVE;
        if (!manager && status != null && !Model.ACTIVE.equals(status)) throw error(HttpStatus.FORBIDDEN, "Không đủ quyền xem lịch sử thành viên");
        String r = role == null || role.isBlank() ? null : validRole(role);
        long total = repo.countMems(orgId, s, r);
        List<Mem> rows = repo.mems(orgId, s, r, p.size(), p.offset());
        Map<UUID, String> names = names(rows.stream().map(Mem::userId).toList());
        return p.page(rows.stream().map(m -> memberView(m, org.status(), names.get(m.userId()))).toList(), total);
    }

    @Transactional
    public MemberView updateMember(Actor actor, UUID orgId, UUID membershipId, UpdateMember req) {
        if (req.role() == null && req.canReview() == null) throw error(HttpStatus.BAD_REQUEST, "Không có thay đổi");
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireManager(me);
        requireActive(org);
        Mem target = repo.mem(orgId, membershipId).orElseThrow(() -> notFound());
        if (!Model.ACTIVE.equals(target.status())) throw error(HttpStatus.CONFLICT, "Thành viên không còn hoạt động");
        checkRevision(target.revision(), req.revision());
        if ("OWNER".equals(target.role())) throw error(HttpStatus.FORBIDDEN, "Không sửa OWNER; dùng chuyển quyền sở hữu");
        boolean owner = "OWNER".equals(me.role());
        String newRole = req.role() == null ? target.role() : req.role().name();
        if (!owner && (!"MEMBER".equals(target.role()) || !"MEMBER".equals(newRole)))
            throw error(HttpStatus.FORBIDDEN, "ADMIN chỉ quản lý MEMBER và không đổi vai trò");
        boolean roleChanged = !newRole.equals(target.role());
        boolean newReview = req.canReview() != null ? req.canReview() : (roleChanged ? false : target.canReview());
        if (newReview && !"MEMBER".equals(newRole)) throw error(HttpStatus.BAD_REQUEST, "canReview chỉ áp dụng cho MEMBER (OWNER/ADMIN đã có BANK_REVIEW)");
        if (!roleChanged && newReview == target.canReview()) throw error(HttpStatus.BAD_REQUEST, "Không có thay đổi");
        OffsetDateTime at = now();
        repo.changeMem(target.id(), newRole, newReview, at);
        if (roleChanged) {
            repo.audit(orgId, actor.userId(), "ROLE_CHANGED", target.userId(), target.id(), null, target.role() + "->" + newRole, at);
            outbox.add("organization.membership.role-changed", event(org, target.userId(), "membershipId", target.id(), "role", newRole, "link", orgLink(orgId)));
        }
        if (newReview != target.canReview()) {
            repo.audit(orgId, actor.userId(), "REVIEW_GRANT_CHANGED", target.userId(), target.id(), null, target.canReview() + "->" + newReview, at);
        }
        return memberView(repo.mem(orgId, membershipId).orElseThrow(), org.status(), null);
    }

    @Transactional
    public void removeMember(Actor actor, UUID orgId, UUID membershipId, long revision) {
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireManager(me);
        requireActive(org);
        Mem target = repo.mem(orgId, membershipId).orElseThrow(() -> notFound());
        if (!Model.ACTIVE.equals(target.status())) throw error(HttpStatus.CONFLICT, "Thành viên không còn hoạt động");
        checkRevision(target.revision(), revision);
        if (target.id().equals(me.id())) throw error(HttpStatus.CONFLICT, "Dùng leave để tự rời tổ chức");
        if ("OWNER".equals(target.role())) throw error(HttpStatus.FORBIDDEN, "Không xóa OWNER; chuyển quyền sở hữu trước");
        if (!"OWNER".equals(me.role()) && !"MEMBER".equals(target.role())) throw error(HttpStatus.FORBIDDEN, "ADMIN chỉ xóa MEMBER");
        OffsetDateTime at = now();
        repo.endMem(target.id(), Model.REVOKED, actor.userId(), at);
        repo.audit(orgId, actor.userId(), "MEMBER_REVOKED", target.userId(), target.id(), null, target.role(), at);
        outbox.add("organization.membership.revoked", event(org, target.userId(), "membershipId", target.id(), "link", "/organizations"));
    }

    @Transactional
    public void leave(Actor actor, UUID orgId) {
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        if ("OWNER".equals(me.role()) && repo.countActiveOwners(orgId) <= 1)
            throw error(HttpStatus.CONFLICT, "OWNER cuối cùng phải chuyển quyền sở hữu trước khi rời");
        OffsetDateTime at = now();
        repo.endMem(me.id(), Model.LEFT, actor.userId(), at);
        repo.audit(orgId, actor.userId(), "MEMBER_LEFT", actor.userId(), me.id(), null, me.role(), at);
    }

    @Transactional
    public OrganizationView transferOwnership(Actor actor, UUID orgId, TransferOwnership req) {
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireRole(me, "OWNER");
        requireActive(org);
        checkRevision(org.revision(), req.revision());
        Mem target = repo.mem(orgId, req.targetMembershipId()).orElseThrow(() -> notFound());
        if (!Model.ACTIVE.equals(target.status())) throw error(HttpStatus.CONFLICT, "Thành viên đích không còn hoạt động");
        if (target.id().equals(me.id())) throw error(HttpStatus.BAD_REQUEST, "Không thể chuyển quyền cho chính mình");
        OffsetDateTime at = now();
        repo.changeMem(target.id(), "OWNER", false, at);   // new OWNER first: the organization never has zero OWNERs
        repo.changeMem(me.id(), "ADMIN", false, at);
        repo.bumpOrg(orgId, at);
        repo.audit(orgId, actor.userId(), "OWNERSHIP_TRANSFERRED", target.userId(), target.id(), null, "from " + actor.userId(), at);
        outbox.add("organization.ownership.transferred", event(org, target.userId(), "membershipId", target.id(), "link", orgLink(orgId)));
        return view(repo.org(orgId).orElseThrow(), repo.mem(orgId, me.id()).orElseThrow(), repo.countActive(orgId));
    }

    @Transactional(readOnly = true)
    public PageView<AuditView> audit(Actor actor, UUID orgId, Paging p) {
        requireManager(member(orgId, actor));
        return p.page(repo.audits(orgId, p.size(), p.offset()).stream().map(a -> new AuditView(a.id(), a.actorUserId(), a.action(),
                a.targetUserId(), a.membershipId(), a.invitationId(), a.detail(), a.occurredAt())).toList(), repo.countAudit(orgId));
    }

    // ================= invitations =================

    public record Created(InvitationView invitation, boolean created) {}

    @Transactional
    public Created invite(Actor actor, UUID orgId, CreateInvitation req) {
        if ((req.userId() == null) == (req.email() == null || req.email().isBlank()))
            throw error(HttpStatus.BAD_REQUEST, "Cần đúng một trong userId hoặc email");
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireManager(me);
        requireActive(org);
        String role = req.role().name();
        if ("ADMIN".equals(role) && !"OWNER".equals(me.role())) throw error(HttpStatus.FORBIDDEN, "Chỉ OWNER mời ADMIN");
        IdentityResolver.Identity recipient = resolveRecipient(req);
        if (recipient.userId().equals(actor.userId())) throw error(HttpStatus.BAD_REQUEST, "Không thể mời chính mình");
        if (repo.activeMem(orgId, recipient.userId()).isPresent()) throw error(HttpStatus.CONFLICT, "Người dùng đã là thành viên");
        OffsetDateTime at = now();
        Optional<Inv> pending = repo.pendingInv(orgId, recipient.userId());
        if (pending.isPresent()) {
            Inv existing = pending.get();
            if (existing.expiresAt().isAfter(at)) {
                if (!existing.role().equals(role)) throw error(HttpStatus.CONFLICT, "Đã có lời mời đang chờ với vai trò khác; thu hồi trước");
                return new Created(invView(existing, org.name(), recipient.displayName()), false);   // duplicate-safe
            }
            expire(existing, org.name());
        }
        Inv inv = new Inv(UUID.randomUUID(), orgId, recipient.userId(), role, actor.userId(), Model.PENDING, at, at.plusDays(invitationTtlDays), null, null, 1);
        repo.insertInv(inv);
        repo.audit(orgId, actor.userId(), "INVITATION_CREATED", recipient.userId(), null, inv.id(), role, at);
        outbox.add("organization.invitation.created", event(org, recipient.userId(), "invitationId", inv.id(), "role", role,
                "invitedByUserId", actor.userId(), "expiresAt", inv.expiresAt().toString(), "link", "/organizations/invitations"));
        return new Created(invView(inv, org.name(), recipient.displayName()), true);
    }

    // The lapsed-invitation EXPIRED write must survive the 409 raised right after it.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public InvitationView revoke(Actor actor, UUID orgId, UUID invitationId) {
        Org org = repo.lockOrg(orgId).orElseThrow(() -> notFound());
        Mem me = member(orgId, actor);
        requireManager(me);
        Inv inv = repo.inv(invitationId).filter(i -> i.organizationId().equals(orgId)).orElseThrow(() -> notFound());
        if (!"OWNER".equals(me.role()) && !"MEMBER".equals(inv.role())) throw error(HttpStatus.FORBIDDEN, "ADMIN chỉ thu hồi lời mời MEMBER");
        OffsetDateTime at = now();
        if (!Model.PENDING.equals(inv.status())) throw error(HttpStatus.CONFLICT, "Lời mời không còn chờ xử lý");
        if (!inv.expiresAt().isAfter(at)) { expire(inv, org.name()); throw error(HttpStatus.CONFLICT, "Lời mời đã hết hạn"); }
        repo.closeInv(inv.id(), Model.REVOKED, null, at);
        repo.audit(orgId, actor.userId(), "INVITATION_REVOKED", inv.recipientUserId(), null, inv.id(), null, at);
        outbox.add("organization.invitation.revoked", event(org, inv.recipientUserId(), "invitationId", inv.id(), "link", "/organizations/invitations"));
        return invView(repo.inv(inv.id()).orElseThrow(), org.name(), null);
    }

    @Transactional(readOnly = true)
    public PageView<InvitationView> invitations(Actor actor, UUID orgId, String status, Paging p) {
        requireManager(member(orgId, actor));
        String s = validInvStatus(status);
        Org org = repo.org(orgId).orElseThrow();
        List<Inv> rows = repo.invs(orgId, s, p.size(), p.offset());
        Map<UUID, String> names = names(rows.stream().map(Inv::recipientUserId).toList());
        return p.page(rows.stream().map(i -> invView(i, org.name(), names.get(i.recipientUserId()))).toList(), repo.countInvs(orgId, s));
    }

    @Transactional(readOnly = true)
    public PageView<InvitationView> myInvitations(Actor actor, String status, Paging p) {
        String s = validInvStatus(status);
        List<Inv> rows = repo.myInvs(actor.userId(), s, p.size(), p.offset());
        Map<UUID, String> orgNames = new HashMap<>();
        return p.page(rows.stream().map(i -> invView(i, orgNames.computeIfAbsent(i.organizationId(), repo::orgName), null)).toList(),
                repo.countMyInvs(actor.userId(), s));
    }

    // The lapsed-invitation EXPIRED write must survive the 409 raised right after it.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public MemberView accept(Actor actor, UUID invitationId) {
        Inv probe = recipientInvitation(actor, invitationId);
        Org org = repo.lockOrg(probe.organizationId()).orElseThrow(() -> notFound());
        Inv inv = repo.inv(invitationId).orElseThrow(() -> notFound());   // re-read under the lock
        OffsetDateTime at = now();
        if (!Model.PENDING.equals(inv.status())) throw error(HttpStatus.CONFLICT, "Lời mời không còn chờ xử lý");
        if (!inv.expiresAt().isAfter(at)) { expire(inv, org.name()); throw error(HttpStatus.CONFLICT, "Lời mời đã hết hạn"); }
        requireActive(org);
        if (repo.activeMem(org.id(), actor.userId()).isPresent()) throw error(HttpStatus.CONFLICT, "Bạn đã là thành viên");
        Mem mem = new Mem(UUID.randomUUID(), org.id(), actor.userId(), inv.role(), Model.ACTIVE, false, at, null, null, at, 1);
        repo.insertMem(mem);
        repo.closeInv(inv.id(), Model.ACCEPTED, mem.id(), at);
        repo.audit(org.id(), actor.userId(), "INVITATION_ACCEPTED", actor.userId(), mem.id(), inv.id(), inv.role(), at);
        outbox.add("organization.invitation.accepted", event(org, inv.invitedBy(), "invitationId", inv.id(), "memberUserId", actor.userId(),
                "link", orgLink(org.id())));
        return memberView(mem, org.status(), null);
    }

    // The lapsed-invitation EXPIRED write must survive the 409 raised right after it.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public InvitationView decline(Actor actor, UUID invitationId) {
        Inv probe = recipientInvitation(actor, invitationId);
        Org org = repo.lockOrg(probe.organizationId()).orElseThrow(() -> notFound());
        Inv inv = repo.inv(invitationId).orElseThrow(() -> notFound());
        OffsetDateTime at = now();
        if (!Model.PENDING.equals(inv.status())) throw error(HttpStatus.CONFLICT, "Lời mời không còn chờ xử lý");
        if (!inv.expiresAt().isAfter(at)) { expire(inv, org.name()); throw error(HttpStatus.CONFLICT, "Lời mời đã hết hạn"); }
        repo.closeInv(inv.id(), Model.DECLINED, null, at);
        repo.audit(org.id(), actor.userId(), "INVITATION_DECLINED", actor.userId(), null, inv.id(), null, at);
        outbox.add("organization.invitation.declined", event(org, inv.invitedBy(), "invitationId", inv.id(), "declinedByUserId", actor.userId(),
                "link", orgLink(org.id())));
        return invView(repo.inv(inv.id()).orElseThrow(), org.name(), null);
    }

    /** Persists lapsed invitations as EXPIRED (reads already report them as EXPIRED). */
    @Scheduled(fixedDelayString = "${organization.events.expiry-sweep-ms:60000}", initialDelayString = "${organization.events.expiry-sweep-ms:60000}")
    @Transactional
    public void sweepExpired() {
        for (Inv inv : repo.lockLapsed(now(), 100)) expire(inv, repo.orgName(inv.organizationId()));
    }

    // ================= helpers =================

    private void expire(Inv inv, String orgName) {
        OffsetDateTime at = now();
        if (!repo.closeInv(inv.id(), Model.EXPIRED, null, at)) return;
        repo.audit(inv.organizationId(), inv.invitedBy(), "INVITATION_EXPIRED", inv.recipientUserId(), null, inv.id(), null, at);
        outbox.add("organization.invitation.expired", Map.of("recipientUserId", inv.recipientUserId(), "invitationId", inv.id(),
                "organizationId", inv.organizationId(), "organizationName", orgName == null ? "" : orgName, "link", "/organizations/invitations"));
    }

    private Inv recipientInvitation(Actor actor, UUID invitationId) {
        // Only the JWT subject that is the recipient may act; others get 404 (no existence leak).
        return repo.inv(invitationId).filter(i -> i.recipientUserId().equals(actor.userId())).orElseThrow(() -> notFound());
    }

    private IdentityResolver.Identity resolveRecipient(CreateInvitation req) {
        try {
            List<IdentityResolver.Identity> found = req.userId() != null ? identities.byUserIds(List.of(req.userId()))
                    : identities.byEmails(List.of(req.email().trim().toLowerCase()));
            return found.stream().filter(i -> req.userId() == null || i.userId().equals(req.userId())).findFirst()
                    .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        } catch (IdentityResolver.Unavailable unavailable) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ danh tính chưa sẵn sàng");
        }
    }

    /** Display names are best-effort cosmetics; authorization never depends on them. */
    private Map<UUID, String> names(Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        try {
            return identities.byUserIds(new LinkedHashSet<>(userIds)).stream().filter(i -> i.displayName() != null)
                    .collect(Collectors.toMap(IdentityResolver.Identity::userId, IdentityResolver.Identity::displayName, (a, b) -> a));
        } catch (IdentityResolver.Unavailable unavailable) {
            return Map.of();
        }
    }

    private Mem member(UUID orgId, Actor actor) {
        return repo.activeMem(orgId, actor.userId()).orElseThrow(() -> notFound());   // non-members cannot probe existence
    }

    private static ResponseStatusException notFound() { return error(HttpStatus.NOT_FOUND, "Không tìm thấy"); }

    private static void requireRole(Mem me, String role) {
        if (!role.equals(me.role())) throw error(HttpStatus.FORBIDDEN, "Cần vai trò " + role);
    }

    private static void requireManager(Mem me) {
        if ("MEMBER".equals(me.role())) throw error(HttpStatus.FORBIDDEN, "Cần vai trò OWNER hoặc ADMIN");
    }

    private static void requireActive(Org org) {
        if (!Model.ACTIVE.equals(org.status())) throw error(HttpStatus.CONFLICT, "Tổ chức đã lưu trữ");
    }

    private static void checkRevision(long current, long supplied) {
        if (current != supplied) throw error(HttpStatus.CONFLICT, "Dữ liệu đã thay đổi (revision hiện tại " + current + ")");
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String orgLink(UUID orgId) { return "/organizations/" + orgId; }

    private static Map<String, Object> event(Org org, UUID recipient, Object... extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recipientUserId", recipient);
        payload.put("organizationId", org.id());
        payload.put("organizationName", org.name());
        for (int i = 0; i < extra.length; i += 2) payload.put((String) extra[i], extra[i + 1]);
        return payload;
    }

    private static String validOrgStatus(String s) {
        if (s == null || s.isBlank()) return null;
        if (!Set.of("ACTIVE", "ARCHIVED").contains(s)) throw error(HttpStatus.BAD_REQUEST, "status không hợp lệ");
        return s;
    }
    private static String validMemStatus(String s) {
        if (s == null || s.isBlank()) return Model.ACTIVE;
        if (s.equals("ALL")) return null;
        if (!Set.of("ACTIVE", "REVOKED", "LEFT").contains(s)) throw error(HttpStatus.BAD_REQUEST, "status không hợp lệ");
        return s;
    }
    private static String validRole(String s) {
        if (!Set.of("OWNER", "ADMIN", "MEMBER").contains(s)) throw error(HttpStatus.BAD_REQUEST, "role không hợp lệ");
        return s;
    }
    private static String validInvStatus(String s) {
        if (s == null || s.isBlank()) return null;
        if (!Set.of("PENDING", "ACCEPTED", "DECLINED", "REVOKED", "EXPIRED").contains(s)) throw error(HttpStatus.BAD_REQUEST, "status không hợp lệ");
        return s;
    }

    private static Access access(UUID membershipId, String role, boolean canReview, String orgStatus) {
        return new Access(membershipId, role, canReview, Model.capabilityNames(role, canReview, orgStatus));
    }
    private static Access access(Mem m, String orgStatus) { return access(m.id(), m.role(), m.canReview(), orgStatus); }

    private static OrganizationView view(Org o, Mem me, Long count) {
        return new OrganizationView(o.id(), o.name(), o.description(), o.status(), o.revision(), o.createdAt(), o.updatedAt(), o.archivedAt(),
                access(me, o.status()), count);
    }

    private static MemberView memberView(Mem m, String orgStatus, String displayName) {
        List<String> caps = Model.ACTIVE.equals(m.status()) ? Model.capabilityNames(m.role(), m.canReview(), orgStatus) : List.of();
        return new MemberView(m.id(), m.userId(), displayName, m.role(), m.status(), m.canReview(), caps, m.joinedAt(), m.endedAt(), m.revision());
    }

    private static InvitationView invView(Inv i, String orgName, String recipientName) {
        String status = Model.PENDING.equals(i.status()) && !i.expiresAt().isAfter(now()) ? Model.EXPIRED : i.status();
        return new InvitationView(i.id(), i.organizationId(), orgName, i.recipientUserId(), recipientName, i.role(), status, i.invitedBy(),
                i.createdAt(), i.expiresAt(), i.respondedAt(), i.revision());
    }
}
