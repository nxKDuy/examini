package com.datn.auth.controller;

import com.datn.auth.dto.AuthRequest;
import com.datn.auth.dto.AuthResponse;
import com.datn.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Đăng ký, đăng nhập, làm mới token")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Đăng ký tài khoản mới")
    public AuthResponse.TokenResponse register(@Valid @RequestBody AuthRequest.RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập, nhận access + refresh token")
    public AuthResponse.TokenResponse login(@Valid @RequestBody AuthRequest.LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Đổi refresh token lấy access token mới")
    public AuthResponse.TokenResponse refresh(@Valid @RequestBody AuthRequest.RefreshRequest req) {
        return authService.refresh(req);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Đăng xuất — thu hồi refresh token", security = @SecurityRequirement(name = "Bearer"))
    public void logout(@RequestHeader("X-User-Id") String userId) {
        authService.logout(userId);
    }

    @GetMapping("/me")
    @Operation(summary = "Lấy thông tin người dùng hiện tại", security = @SecurityRequirement(name = "Bearer"))
    public AuthResponse.UserInfo me(@RequestHeader("X-User-Id") String userId) {
        return authService.me(userId);
    }
}
