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

            // ponytail: default STUDENT, admin tự cấp quyền sau
            com.datn.auth.entity.User.Role role
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}
}
