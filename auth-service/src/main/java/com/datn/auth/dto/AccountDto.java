package com.datn.auth.dto;

import com.datn.auth.entity.User;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Personal account (profile, password, linked sign-in) and the admin user directory. */
public final class AccountDto {
    private AccountDto() {}

    public record Profile(UUID id, String email, String fullName, User.Role role, User.Persona persona, User.SystemPrivilege systemPrivilege,
                          String phone, String school, String bio, Instant avatarUpdatedAt, boolean passwordSet, boolean googleLinked,
                          LocalDateTime createdAt, Instant lastLoginAt) {}

    public record UpdateProfile(
            @NotBlank(message = "Họ tên không được để trống") @Size(max = 255) String fullName,
            @Size(max = 30) @Pattern(regexp = "^$|^[+0-9 ().-]{6,30}$", message = "Số điện thoại không hợp lệ") String phone,
            @Size(max = 255) String school,
            @Size(max = 1000) String bio) {}

    /** currentPassword is required unless the account has no password yet (created with Google). */
    public record ChangePassword(@Size(max = 200) String currentPassword,
                                 @NotBlank @Size(min = 8, max = 200, message = "Mật khẩu tối thiểu 8 ký tự") String newPassword) {}

    public record LinkGoogle(@NotBlank @Size(max = 4096) String credential) {}

    public record ForgotPassword(@NotBlank @Email(message = "Email không hợp lệ") @Size(max = 255) String email) {}
    public record ResetPassword(@NotBlank @Size(max = 200) String token,
                                @NotBlank @Size(min = 8, max = 200, message = "Mật khẩu tối thiểu 8 ký tự") String newPassword) {}

    public record AdminUser(UUID id, String email, String fullName, User.Role role, User.Persona persona, User.SystemPrivilege systemPrivilege,
                            boolean locked, Instant lockedAt, boolean googleLinked, boolean passwordSet, LocalDateTime createdAt, Instant lastLoginAt) {}
    public record AdminPage(List<AdminUser> content, long totalElements, int totalPages, int number, int size) {}
    public record AdminUpdate(@NotNull Boolean locked) {}
    public record ResetLink(String url, Instant expiresAt, boolean emailed) {}
}
