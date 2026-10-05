package com.datn.questionbank.security;

import java.util.UUID;

/** Identity supplied only by the service authentication boundary, never request-body ownership. */
public record QuestionActor(UUID userId, String role, boolean internal) {
    public boolean admin() { return !internal && "ADMIN".equals(role); }
    public boolean author() { return !internal && (admin() || "TEACHER".equals(role)); }
}
