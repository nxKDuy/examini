package com.datn.auth.dto;

import com.datn.auth.entity.User;

import java.util.UUID;

public class AuthResponse {
    /** Sign-in options for the login page; the Google client id is public by design. */
    public record ProvidersResponse(boolean google, String googleClientId) {}

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
            User.Role role,
            User.Persona persona,
            User.SystemPrivilege systemPrivilege
    ) {}
}
