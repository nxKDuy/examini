package com.datn.organization.domain;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistence rows (immutable) and the role/capability model. Roles are organization-scoped, unrelated to Auth roles. */
public final class Model {
    private Model() {}

    public enum Role { OWNER, ADMIN, MEMBER }

    public enum Capability { BANK_READ, BANK_USE, BANK_SUBMIT, BANK_REVIEW, BANK_MANAGE }

    public static final String ACTIVE = "ACTIVE", ARCHIVED = "ARCHIVED", REVOKED = "REVOKED", LEFT = "LEFT";
    public static final String PENDING = "PENDING", ACCEPTED = "ACCEPTED", DECLINED = "DECLINED", EXPIRED = "EXPIRED";

    /** Capabilities of an ACTIVE membership; ARCHIVED organizations keep read-only access to history. */
    public static Set<Capability> capabilities(String role, boolean canReview, String organizationStatus) {
        if (!ACTIVE.equals(organizationStatus)) return EnumSet.of(Capability.BANK_READ);
        return switch (Role.valueOf(role)) {
            case OWNER, ADMIN -> EnumSet.allOf(Capability.class);
            case MEMBER -> {
                EnumSet<Capability> caps = EnumSet.of(Capability.BANK_READ, Capability.BANK_USE, Capability.BANK_SUBMIT);
                if (canReview) caps.add(Capability.BANK_REVIEW);
                yield caps;
            }
        };
    }

    public static List<String> capabilityNames(String role, boolean canReview, String organizationStatus) {
        return capabilities(role, canReview, organizationStatus).stream().sorted().map(Enum::name).toList();
    }

    public record Org(UUID id, String name, String description, String status, UUID createdBy, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt, OffsetDateTime archivedAt, long revision) {}

    public record Mem(UUID id, UUID organizationId, UUID userId, String role, String status, boolean canReview,
                      OffsetDateTime joinedAt, OffsetDateTime endedAt, UUID endedBy, OffsetDateTime updatedAt, long revision) {}

    public record Inv(UUID id, UUID organizationId, UUID recipientUserId, String role, UUID invitedBy, String status,
                      OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime respondedAt, UUID membershipId, long revision) {}

    public record Audit(UUID id, UUID organizationId, UUID actorUserId, String action, UUID targetUserId, UUID membershipId,
                        UUID invitationId, String detail, OffsetDateTime occurredAt) {}
}
