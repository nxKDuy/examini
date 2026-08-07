package com.datn.auth.service;

import com.datn.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Tạo và validate JWT access + refresh tokens.
 * ponytail: dùng JJWT 0.12 API, không tự viết crypto.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiry-ms:900000}") long accessExpiryMs,
            @Value("${jwt.refresh-expiry-ms:604800000}") long refreshExpiryMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiryMs = accessExpiryMs;
        this.refreshExpiryMs = refreshExpiryMs;
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiryMs))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiryMs))
                .signWith(key)
                .compact();
    }

    public Claims validateAndExtract(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessExpirySeconds() {
        return accessExpiryMs / 1000;
    }

    public long getRefreshExpiryMs() {
        return refreshExpiryMs;
    }
}
