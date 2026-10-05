package com.datn.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private final JwtAuthFilter filter = new JwtAuthFilter();
    private final GatewayFilter gatewayFilter = filter.apply(new JwtAuthFilter.Config());

    @BeforeEach
    void setSecret() {
        ReflectionTestUtils.setField(filter, "secret", SECRET);
    }

    @Test
    void overwritesAllSpoofedIdentityHeadersFromVerifiedAccessToken() {
        String userId = UUID.randomUUID().toString();
        ServerWebExchange forwarded = forward(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId, "STUDENT", "STUDENT", "NONE", "user@example.com", "access"))
                .header("X-User-Id", "user-B")
                .header("X-User-Email", "attacker@example.com")
                .header("X-User-Role", "ADMIN")
                .header("X-User-Persona", "TEACHER")
                .header("X-System-Privilege", "ADMIN")
                .build());

        assertEquals(userId, forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("user@example.com", forwarded.getRequest().getHeaders().getFirst("X-User-Email"));
        assertEquals("STUDENT", forwarded.getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("STUDENT", forwarded.getRequest().getHeaders().getFirst("X-User-Persona"));
        assertEquals("NONE", forwarded.getRequest().getHeaders().getFirst("X-System-Privilege"));
    }

    @Test
    void acceptsAccessTokenOnProtectedRouteAndKeepsLegitimateAdminPrivilege() {
        ServerWebExchange forwarded = forward(MockServerHttpRequest.get("/exams")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID().toString(), "ADMIN", null, "ADMIN", "admin@example.com", "access"))
                .build());

        assertEquals("ADMIN", forwarded.getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("ADMIN", forwarded.getRequest().getHeaders().getFirst("X-System-Privilege"));
    }

    @Test
    void legacyPersonalRoleSuppliesPersonaAndMissingPrivilegeDefaultsToNone() {
        ServerWebExchange forwarded = forward(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID().toString(), "TEACHER", null, null, null, "access"))
                .build());

        assertEquals("TEACHER", forwarded.getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("TEACHER", forwarded.getRequest().getHeaders().getFirst("X-User-Persona"));
        assertEquals("NONE", forwarded.getRequest().getHeaders().getFirst("X-System-Privilege"));
    }

    @Test
    void rejectsRefreshExpiredTamperedMalformedAndMissingTokensOnProtectedRoutes() {
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID().toString(), "STUDENT", "STUDENT", "NONE", null, "refresh"))
                .build());
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken())
                .build());
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWithKey(UUID.randomUUID().toString(), "access", Keys.hmacShaKeyFor("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8))))
                .build());
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .build());
        assertRejected(MockServerHttpRequest.get("/questions").build());
    }

    @Test
    void rejectsMissingRequiredClaimsAndInconsistentPrivilegeClaims() {
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + Jwts.builder().claim("type", "access")
                        .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact())
                .build());
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + Jwts.builder().subject(UUID.randomUUID().toString())
                        .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact())
                .build());
        assertRejected(MockServerHttpRequest.get("/questions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID().toString(), "ADMIN", null, "NONE", null, "access"))
                .build());
    }

    @Test
    void publicAuthAndWebSocketRequestsRemainAvailableButIdentityHeadersAreStripped() {
        for (String path : new String[]{"/auth/login", "/auth/register", "/auth/refresh",
                "/auth-service/auth/login", "/auth-service/auth/register", "/auth-service/auth/refresh"}) {
            ServerWebExchange forwarded = forward(MockServerHttpRequest.post(path)
                    .header("X-User-Id", "spoof")
                    .header("X-User-Role", "ADMIN")
                    .header("X-System-Privilege", "ADMIN")
                    .build());
            assertNull(forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
            assertNull(forwarded.getRequest().getHeaders().getFirst("X-User-Role"));
            assertNull(forwarded.getRequest().getHeaders().getFirst("X-System-Privilege"));
        }

        ServerWebExchange websocket = forward(MockServerHttpRequest.get("/ws/info")
                .header("X-User-Id", "spoof")
                .header("X-System-Privilege", "ADMIN")
                .build());
        assertNull(websocket.getRequest().getHeaders().getFirst("X-User-Id"));
        assertNull(websocket.getRequest().getHeaders().getFirst("X-System-Privilege"));
    }

    @Test
    void authLogoutAndMeAreProtectedAndPublicPatternsAreExact() {
        assertRejected(MockServerHttpRequest.post("/auth/logout").build());
        assertRejected(MockServerHttpRequest.get("/auth/me").build());
        assertRejected(MockServerHttpRequest.post("/auth/login/anything").build());
        assertRejected(MockServerHttpRequest.get("/auth/login").build());
    }

    private ServerWebExchange forward(MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        gatewayFilter.filter(exchange, next -> {
            forwarded.set(next);
            return Mono.empty();
        }).block();
        assertNotNull(forwarded.get(), "request should be forwarded");
        return forwarded.get();
    }

    private void assertRejected(MockServerHttpRequest request) {
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);
        gatewayFilter.filter(exchange, next -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertFalse(forwarded.get(), "invalid request must not reach downstream");
    }

    private static String token(String subject, String role, String persona, String privilege, String email, String type) {
        var builder = Jwts.builder().subject(subject).claim("type", type);
        if (role != null) builder.claim("role", role);
        if (persona != null) builder.claim("persona", persona);
        if (privilege != null) builder.claim("systemPrivilege", privilege);
        if (email != null) builder.claim("email", email);
        return builder.expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact();
    }

    private static String tokenWithKey(String subject, String type, SecretKey signingKey) {
        return Jwts.builder().subject(subject).claim("type", type)
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(signingKey).compact();
    }

    private static String expiredToken() {
        return Jwts.builder().subject(UUID.randomUUID().toString()).claim("type", "access")
                .claim("role", "STUDENT").claim("persona", "STUDENT").claim("systemPrivilege", "NONE")
                .expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(KEY).compact();
    }
}
