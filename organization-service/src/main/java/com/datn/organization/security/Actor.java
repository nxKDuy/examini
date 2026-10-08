package com.datn.organization.security;

import java.util.UUID;

/** Identity from a verified Auth access JWT only. role is the Auth role (TEACHER/STUDENT/ADMIN); never an organization role. */
public record Actor(UUID userId, String role) {
    public boolean teacher() { return "TEACHER".equals(role); }
}
