package com.datn.questionbank.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.List;

/** Online, fail-closed adapter for the Organization-owned authorization contract. */
@Component
public class OrganizationAuthorizationClient {
    public enum Capability { BANK_READ, BANK_USE, BANK_SUBMIT, BANK_REVIEW, BANK_MANAGE }

    private final RestClient client;
    private final String token;

    public OrganizationAuthorizationClient(
            @Value("${organization.authorization.url:http://localhost:8088}") String url,
            @Value("${organization.authorization.token:}") String token) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(1500);
        requests.setReadTimeout(2500);
        this.client = RestClient.builder().baseUrl(url).requestFactory(requests).build();
        this.token = token;
    }

    public void require(UUID organizationId, UUID userId, Capability capability) {
        if (organizationId == null || userId == null || token.isBlank()) denyUnavailable();
        try {
            var decision = client.post().uri("/organizations/internal/authorize")
                    .header("X-Service-Token", token)
                    .body(new AuthorizationRequest(organizationId, userId, capability))
                    .retrieve().body(AuthorizationDecision.class);
            if (decision == null || !decision.allowed()
                    || !organizationId.equals(decision.organizationId())
                    || !("ACTIVE".equals(decision.organizationStatus())
                        || capability == Capability.BANK_READ && "ARCHIVED".equals(decision.organizationStatus()))
                    || !"ACTIVE".equals(decision.membershipStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Organization capability bị từ chối");
            }
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            denyUnavailable();
        }
    }

    /** Discovery only narrows list results; every operation still calls {@link #require}. */
    public List<Membership> memberships(UUID userId, Capability capability) {
        if (userId == null || token.isBlank()) denyUnavailable();
        try {
            var result = client.post().uri("/organizations/internal/memberships")
                    .header("X-Service-Token", token)
                    .body(new MembershipRequest(userId, capability))
                    .retrieve().body(MembershipResponse.class);
            if (result == null || !userId.equals(result.userId()) || result.memberships() == null)
                denyUnavailable();
            return result.memberships().stream()
                    .filter(m -> m.organizationId() != null
                            && "ACTIVE".equals(m.membershipStatus())
                            && ("ACTIVE".equals(m.organizationStatus()) || capability == Capability.BANK_READ)
                            && m.capabilities() != null && m.capabilities().contains(capability))
                    .toList();
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            denyUnavailable();
            return List.of(); // unreachable
        }
    }

    private void denyUnavailable() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Organization authorization provider không khả dụng");
    }

    private record AuthorizationRequest(UUID organizationId, UUID userId, Capability capability) {}
    private record AuthorizationDecision(boolean allowed, UUID organizationId,
            String organizationStatus, UUID membershipId, String membershipStatus,
            String role, String decisionAt) {}
    private record MembershipRequest(UUID userId, Capability capability) {}
    private record MembershipResponse(UUID userId, List<Membership> memberships,
            boolean truncated, String decisionAt) {}
    public record Membership(UUID organizationId, String organizationName,
            String organizationStatus, UUID membershipId, String membershipStatus,
            String role, boolean canReview, List<Capability> capabilities) {}
}
