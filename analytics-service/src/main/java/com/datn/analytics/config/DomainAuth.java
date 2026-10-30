package com.datn.analytics.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** Same trusted-identity model as the Exam Taking services: verify the access JWT, ignore identity headers. */
@Configuration
public class DomainAuth implements WebMvcConfigurer, HandlerInterceptor {
    @Value("${jwt.secret:}") private String jwtSecret = "";

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).excludePathPatterns("/actuator/health", "/error");
    }

    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String bearer = req.getHeader("Authorization");
        if (Collections.list(req.getHeaders("Authorization")).size() > 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Duplicate authentication headers");
        // Analytics exposes no internal routes; a service token is never a user identity here.
        if (req.getHeader("X-Service-Token") != null || bearer == null || !bearer.startsWith("Bearer ") || jwtSecret.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token required");
        try {
            var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).build()
                    .parseSignedClaims(bearer.substring(7)).getPayload();
            String role = claims.get("role", String.class);
            if (!"access".equals(claims.get("type", String.class)) || claims.getExpiration() == null
                    || !Set.of("TEACHER", "ADMIN", "STUDENT").contains(role)) throw new IllegalArgumentException();
            // No candidate-safe analytics endpoint exists yet: students get no aggregate or participant data.
            if ("STUDENT".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Teacher role required");
            req.setAttribute("actor", UUID.fromString(claims.getSubject()));
            req.setAttribute("actorRole", role);
            req.setAttribute("systemPrivilege", String.valueOf(claims.get("systemPrivilege", String.class)));
            return true;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid access token");
        }
    }
}
