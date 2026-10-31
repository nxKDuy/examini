package com.datn.auth.controller;

import com.datn.auth.dto.AccountDto.*;
import com.datn.auth.dto.AuthResponse;
import com.datn.auth.service.AccountService;
import com.datn.auth.service.AuthService;
import com.datn.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.UUID;

@RestController @RequestMapping("/auth") @RequiredArgsConstructor
@Tag(name = "Account", description = "Hồ sơ cá nhân, mật khẩu, liên kết Google, quản trị người dùng")
public class AccountController {
    private final AuthService auth;
    private final AccountService accounts;
    private final PasswordResetService resets;

    private UUID me(String authorization) { return UUID.fromString(auth.authenticatedUserId(authorization)); }

    @GetMapping("/account") @Operation(summary = "Hồ sơ của tôi")
    public Profile get(@RequestHeader("Authorization") String a) { return accounts.get(me(a)); }

    @PatchMapping("/account") @Operation(summary = "Sửa hồ sơ")
    public Profile update(@RequestHeader("Authorization") String a, @Valid @RequestBody UpdateProfile body) { return accounts.update(me(a), body); }

    @PutMapping("/account/avatar") @Operation(summary = "Đổi ảnh đại diện (body = ảnh JPEG/PNG/WebP ≤ 300 KB)")
    public Profile setAvatar(@RequestHeader("Authorization") String a, HttpServletRequest request) throws IOException {
        UUID id = me(a);
        return accounts.setAvatar(id, request.getInputStream().readNBytes(300 * 1024 + 1));
    }

    @DeleteMapping("/account/avatar") @Operation(summary = "Xóa ảnh đại diện")
    public Profile removeAvatar(@RequestHeader("Authorization") String a) { return accounts.removeAvatar(me(a)); }

    @GetMapping("/account/avatar") @Operation(summary = "Ảnh đại diện của tôi")
    public ResponseEntity<byte[]> avatar(@RequestHeader("Authorization") String a) {
        var av = accounts.avatar(me(a));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.parseMediaType(av.getContentType())).body(av.getContent());
    }

    @PostMapping("/account/password") @Operation(summary = "Đổi/đặt mật khẩu; trả phiên mới và đăng xuất các phiên khác")
    public AuthResponse.TokenResponse changePassword(@RequestHeader("Authorization") String a, @Valid @RequestBody ChangePassword body) {
        return accounts.changePassword(me(a), body);
    }

    @PostMapping("/account/google") @Operation(summary = "Liên kết tài khoản Google")
    public Profile linkGoogle(@RequestHeader("Authorization") String a, @Valid @RequestBody LinkGoogle body) { return accounts.linkGoogle(me(a), body); }

    @DeleteMapping("/account/google") @Operation(summary = "Hủy liên kết Google")
    public Profile unlinkGoogle(@RequestHeader("Authorization") String a) { return accounts.unlinkGoogle(me(a)); }

    @PostMapping("/password/forgot") @ResponseStatus(HttpStatus.ACCEPTED) @Operation(summary = "Gửi liên kết đặt lại mật khẩu (luôn trả 202)")
    public void forgot(@Valid @RequestBody ForgotPassword body) { resets.forgot(body.email()); }

    @PostMapping("/password/reset") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary = "Đặt mật khẩu mới bằng liên kết")
    public void reset(@Valid @RequestBody ResetPassword body) { resets.reset(body.token(), body.newPassword()); }

    @GetMapping("/admin/users") @Operation(summary = "Quản trị: danh sách người dùng")
    public AdminPage users(@RequestHeader("Authorization") String a, @RequestParam(defaultValue = "") String q,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return accounts.list(me(a), q, page, size);
    }

    @PatchMapping("/admin/users/{id}") @Operation(summary = "Quản trị: khóa/mở khóa tài khoản")
    public AdminUser setLocked(@RequestHeader("Authorization") String a, @PathVariable UUID id, @Valid @RequestBody AdminUpdate body) {
        return accounts.setLocked(me(a), id, body.locked());
    }

    @PostMapping("/admin/users/{id}/password-reset") @Operation(summary = "Quản trị: tạo liên kết đặt lại mật khẩu")
    public ResetLink resetLink(@RequestHeader("Authorization") String a, @PathVariable UUID id) { return accounts.resetLink(me(a), id); }
}
