package com.datn.organization.client;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Auth-owned identity resolution (contracts/identity-resolution.md). Organization stores only userId references. */
public interface IdentityResolver {
    record Identity(UUID userId, String displayName, String email, String persona) {}

    /** Provider missing, misconfigured or failing: callers needing certainty must fail closed (503). */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }

    List<Identity> byUserIds(Collection<UUID> userIds);

    List<Identity> byEmails(Collection<String> emails);
}
