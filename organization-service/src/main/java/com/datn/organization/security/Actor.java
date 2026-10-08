package com.datn.organization.security;

import java.util.UUID;

/**
 * Identity from a verified Auth access JWT only. persona is the canonical STUDENT/TEACHER claim (null when absent);
 * role is the legacy Auth role kept for compatibility. Neither is an organization role.
 */
public record Actor(UUID userId, String role, String persona) {
    /** Canonical persona wins; the legacy role only decides when persona is absent. Never implies ADMIN or any Org right. */
    public boolean teacher() { return persona != null ? "TEACHER".equals(persona) : "TEACHER".equals(role); }
}
