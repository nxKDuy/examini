package com.datn.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthRequest {

    public record LoginRequest(
            @Email(message = "Email không hợp lệ")
            @NotBlank String email,

            @NotBlank String password
    ) {}

    public record RegisterRequest(
            @Email(message = "Email không hợp lệ")
            @NotBlank String email,

            @NotBlank
            @Size(min = 8, message = "Mật khẩu tối thiểu 8 ký tự")
            String password,

            @NotBlank
            @Size(max = 255) String fullName,

            AccountType accountType,
            Persona persona,
            // Kept for existing clients; privileged values are rejected.
            com.datn.auth.entity.User.Role role
    ) {
        public enum AccountType { PERSONAL, BUSINESS }
        public enum Persona { STUDENT, TEACHER }
    }

    /** credential = Google ID token from Google Identity Services; persona only when creating a new account. */
    public record GoogleRequest(
            @NotBlank @Size(max = 4096) String credential,
            RegisterRequest.Persona persona
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}
}
