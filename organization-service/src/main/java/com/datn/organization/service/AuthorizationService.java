package com.datn.organization.service;

import com.datn.organization.domain.Dto.*;
import com.datn.organization.domain.Model;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Authoritative, uncached decisions for Question Bank. Always reads current rows, so revoke/leave/archive apply immediately. */
@Service
public class AuthorizationService {
    static final int DISCOVERY_LIMIT = 200;
    private final Repo repo;

    public AuthorizationService(Repo repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public AuthorizeResponse authorize(AuthorizeRequest req) {
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC);
        var decision = repo.decision(req.organizationId(), req.userId());
        if (decision.isEmpty()) return new AuthorizeResponse(false, req.organizationId(), null, null, null, null, null, null, null, at);
        var d = decision.get();
        if (d.membershipId() == null)   // non-member / revoked / left
            return new AuthorizeResponse(false, req.organizationId(), d.orgName(), d.orgStatus(), null, null, null, null, null, at);
        var caps = Model.capabilities(d.role(), d.canReview(), d.orgStatus());
        return new AuthorizeResponse(caps.contains(req.capability()), req.organizationId(), d.orgName(), d.orgStatus(), d.membershipId(),
                Model.ACTIVE, d.role(), d.canReview(), Model.capabilityNames(d.role(), d.canReview(), d.orgStatus()), at);
    }

    @Transactional(readOnly = true)
    public DiscoveryResponse discover(DiscoveryRequest req) {
        List<Repo.Discovered> rows = repo.discover(req.userId(), DISCOVERY_LIMIT + 1);
        boolean truncated = rows.size() > DISCOVERY_LIMIT;
        List<DiscoveryItem> items = rows.stream().limit(DISCOVERY_LIMIT)
                .filter(r -> req.capability() == null || Model.capabilities(r.role(), r.canReview(), r.orgStatus()).contains(req.capability()))
                .map(r -> new DiscoveryItem(r.orgId(), r.orgName(), r.orgStatus(), r.membershipId(), Model.ACTIVE, r.role(), r.canReview(),
                        Model.capabilityNames(r.role(), r.canReview(), r.orgStatus()))).toList();
        return new DiscoveryResponse(req.userId(), items, truncated, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
