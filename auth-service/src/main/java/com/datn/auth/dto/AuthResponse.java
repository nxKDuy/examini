package com.datn.auth.dto;

import com.datn.auth.entity.User;

import java.util.UUID;

public class AuthResponse {

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,       // seconds
            String tokenType      // "Bearer"
    ) {}

    public record UserInfo(
            UUID id,
            String email,
            String fullName,
            User.Role role
    ) {}
}
