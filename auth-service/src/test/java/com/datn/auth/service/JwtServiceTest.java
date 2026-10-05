package com.datn.auth.service;

import com.datn.auth.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtServiceTest {
    private static final String SECRET = "a-secure-secret-that-is-at-least-32-bytes-long";

    @Test
    void accessClaimsComeFromPersistedUserState() {
        var service = new JwtService(SECRET, 60_000, 120_000);
        var user = User.builder().id(UUID.randomUUID()).email("teacher@example.com").role(User.Role.TEACHER)
                .personalPersona(User.Persona.TEACHER).systemPrivilege(User.SystemPrivilege.NONE).build();

        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(service.generateAccessToken(user)).getPayload();

        assertEquals("TEACHER", claims.get("role", String.class));
        assertEquals("TEACHER", claims.get("persona", String.class));
        assertEquals("NONE", claims.get("systemPrivilege", String.class));
    }

    @Test
    void existingSystemAdminPrivilegeIsPreservedInAccessToken() {
        var service = new JwtService(SECRET, 60_000, 120_000);
        var user = User.builder().id(UUID.randomUUID()).email("admin@example.com").role(User.Role.ADMIN)
                .systemPrivilege(User.SystemPrivilege.ADMIN).build();
        var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(service.generateAccessToken(user)).getPayload();

        assertEquals("ADMIN", claims.get("role", String.class));
        assertEquals("ADMIN", claims.get("systemPrivilege", String.class));
    }
}
