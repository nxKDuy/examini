package com.datn.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Validates access JWTs and creates the trusted identity headers sent downstream. */
@Component
public class JwtAuthFilter extends AbstractGatewayFilterFactory<JwtAuthFilter.Config> {

    private static final List<String> IDENTITY_HEADERS = List.of(
            "X-User-Id",
            "X-User-Email",
            "X-User-Role",
            "X-User-Persona",
            "X-System-Privilege",
            "X-Service-Token",
            "X-Question-Owner-Id"
    );
    /** Read-only sign-in configuration (public Google client id); GET only. */
    private static final Set<String> PUBLIC_AUTH_GET_PATHS = Set.of("/auth/providers", "/auth-service/auth/providers");
    /** Calendar subscriptions: apps cannot log in, the random token in the path is checked by exam-service. */
    private static final java.util.regex.Pattern CALENDAR_FEED = java.util.regex.Pattern.compile("/exams/calendar/feed/[A-Za-z0-9_-]{20,64}\\.ics");
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/auth/login", "/auth/register", "/auth/refresh", "/auth/google", "/auth/password/forgot", "/auth/password/reset",
            "/auth-service/auth/login", "/auth-service/auth/register", "/auth-service/auth/refresh", "/auth-service/auth/google",
            "/auth-service/auth/password/forgot", "/auth-service/auth/password/reset"
    );
    private static final Set<String> ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");
    private static final Set<String> PERSONAS = Set.of("STUDENT", "TEACHER");
    private static final Set<String> PRIVILEGES = Set.of("NONE", "ADMIN");

    @Value("${jwt.secret}")
    private String secret;

    public JwtAuthFilter() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // Internal APIs are reached on the service network, never through this public gateway.
            if (isInternalOrAmbiguousPath(exchange)) {
                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                return exchange.getResponse().setComplete();
            }
            ServerWebExchange sanitized = stripIdentityHeaders(exchange);
            if (isPublicAuthRequest(exchange)) {
                return chain.filter(sanitized);
            }

            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ") || authHeader.length() == 7) {
                return unauthorized(exchange);
            }

            try {
                SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
                Claims claims = Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(authHeader.substring(7))
                        .getPayload();

                TrustedIdentity identity = trustedIdentity(claims);
                ServerWebExchange authenticated = sanitized.mutate()
                        .request(request -> request.headers(headers -> identity.writeTo(headers)))
                        .build();
                return chain.filter(authenticated);
            } catch (JwtException | IllegalArgumentException | ClassCastException e) {
                return unauthorized(exchange);
            }
        };
    }

    private boolean isInternalOrAmbiguousPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        // Reject another encoding layer; downstream decoding must not reveal a hidden internal route.
        if (path.contains("%")) return true;
        for (String segment : path.replace('\\', '/').split("/")) {
            if ("internal".equalsIgnoreCase(segment.split(";", 2)[0])) return true;
        }
        return false;
    }

    private ServerWebExchange stripIdentityHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> IDENTITY_HEADERS.forEach(headers::remove)))
                .build();
    }

    private boolean isPublicAuthRequest(ServerWebExchange exchange) {
        String path = exchange.getRequest().getURI().getPath();
        return HttpMethod.POST.equals(exchange.getRequest().getMethod()) && PUBLIC_AUTH_PATHS.contains(path)
                || HttpMethod.GET.equals(exchange.getRequest().getMethod()) && (PUBLIC_AUTH_GET_PATHS.contains(path) || CALENDAR_FEED.matcher(path).matches());
    }

    private TrustedIdentity trustedIdentity(Claims claims) {
        String subject = claims.getSubject();
        String type = claims.get("type", String.class);
        if (subject == null || subject.isBlank() || !"access".equals(type)) {
            throw new JwtException("Invalid access token claims");
        }

        String role = claims.get("role", String.class);
        String persona = claims.get("persona", String.class);
        String privilege = claims.get("systemPrivilege", String.class);
        String email = claims.get("email", String.class);

        if (role != null && !ROLES.contains(role)) throw new JwtException("Invalid role claim");
        if (privilege == null) privilege = "NONE";
        if (!PRIVILEGES.contains(privilege)) throw new JwtException("Invalid system privilege claim");

        // Older access tokens have role but no persona. Only personal legacy roles map to persona.
        if (persona == null && PERSONAS.contains(role)) persona = role;
        if (persona != null && !PERSONAS.contains(persona)) throw new JwtException("Invalid persona claim");

        // Reject conflicting signed claims; in particular, ADMIN requires the explicit privilege claim.
        if ("ADMIN".equals(role) != "ADMIN".equals(privilege)) {
            throw new JwtException("Inconsistent role and privilege claims");
        }
        if (PERSONAS.contains(role) && persona != null && !role.equals(persona)) {
            throw new JwtException("Inconsistent role and persona claims");
        }
        if ("ADMIN".equals(privilege) && persona != null) {
            throw new JwtException("Inconsistent admin persona claim");
        }

        return new TrustedIdentity(subject, email, role, persona, privilege);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private record TrustedIdentity(String id, String email, String role, String persona, String privilege) {
        private void writeTo(HttpHeaders headers) {
            headers.set("X-User-Id", id);
            setIfPresent(headers, "X-User-Email", email);
            setIfPresent(headers, "X-User-Role", role);
            setIfPresent(headers, "X-User-Persona", persona);
            headers.set("X-System-Privilege", privilege);
        }

        private static void setIfPresent(HttpHeaders headers, String name, String value) {
            if (value != null) headers.set(name, value);
        }
    }

    public static class Config {}
}
