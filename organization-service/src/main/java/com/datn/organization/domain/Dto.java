package com.datn.organization.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Public + internal request/response shapes. Responses omit nulls; none carries an email or credential. */
public final class Dto {
    private Dto() {}

    public record CreateOrganization(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description) {}

    /** name/description optional but at least one required; revision is mandatory (optimistic concurrency). */
    public record UpdateOrganization(@Size(min = 1, max = 120) String name, @Size(max = 1000) String description,
                                     @NotNull @Min(1) Long revision) {}

    public record Revision(@NotNull @Min(1) Long revision) {}

    public record UpdateMember(Role role, Boolean canReview, @NotNull @Min(1) Long revision) {
        public enum Role { ADMIN, MEMBER }
    }

    public record TransferOwnership(@NotNull UUID targetMembershipId, @NotNull @Min(1) Long revision) {}

    /** Exactly one of userId / email. Invited role is ADMIN or MEMBER. */
    public record CreateInvitation(UUID userId, @Email @Size(max = 254) String email, @NotNull UpdateMember.Role role) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Access(UUID membershipId, String role, boolean canReview, List<String> capabilities) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrganizationView(UUID id, String name, String description, String status, long revision, OffsetDateTime createdAt,
                                   OffsetDateTime updatedAt, OffsetDateTime archivedAt, Access me, Long activeMemberCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemberView(UUID membershipId, UUID userId, String displayName, String role, String status, boolean canReview,
                             List<String> capabilities, OffsetDateTime joinedAt, OffsetDateTime endedAt, long revision) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvitationView(UUID id, UUID organizationId, String organizationName, UUID recipientUserId, String recipientDisplayName,
                                 String role, String status, UUID invitedBy, OffsetDateTime createdAt, OffsetDateTime expiresAt,
                                 OffsetDateTime respondedAt, long revision) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditView(UUID id, UUID actorUserId, String action, UUID targetUserId, UUID membershipId, UUID invitationId,
                            String detail, OffsetDateTime occurredAt) {}

    public record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}

    // ---- internal (Question Bank) ----
    public record AuthorizeRequest(@NotNull UUID organizationId, @NotNull UUID userId, @NotNull Model.Capability capability) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthorizeResponse(boolean allowed, UUID organizationId, String organizationName, String organizationStatus,
                                    UUID membershipId, String membershipStatus, String role, Boolean canReview,
                                    List<String> capabilities, OffsetDateTime decisionAt) {}

    public record DiscoveryRequest(@NotNull UUID userId, Model.Capability capability) {}

    public record DiscoveryItem(UUID organizationId, String organizationName, String organizationStatus, UUID membershipId,
                                String membershipStatus, String role, boolean canReview, List<String> capabilities) {}

    public record DiscoveryResponse(UUID userId, List<DiscoveryItem> memberships, boolean truncated, OffsetDateTime decisionAt) {}
}
