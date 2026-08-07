package com.datn.auth.service;

import com.datn.auth.dto.AuthRequest;
import com.datn.auth.dto.AuthResponse;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;

    private static final String REFRESH_KEY_PREFIX = "refresh:";

    @Transactional
    public AuthResponse.TokenResponse register(AuthRequest.RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã tồn tại");
        }
        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .role(req.role() != null ? req.role() : User.Role.STUDENT)
                .build();
        userRepository.save(user);
        return issueTokens(user);
    }

    public AuthResponse.TokenResponse login(AuthRequest.LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sai email hoặc mật khẩu"));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sai email hoặc mật khẩu");
        }
        return issueTokens(user);
    }

    public AuthResponse.TokenResponse refresh(AuthRequest.RefreshRequest req) {
        Claims claims;
        try {
            claims = jwtService.validateAndExtract(req.refreshToken());
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token không hợp lệ hoặc đã hết hạn");
        }

        String userId = claims.getSubject();
        String storedToken = redis.opsForValue().get(REFRESH_KEY_PREFIX + userId);
        if (!req.refreshToken().equals(storedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token đã bị thu hồi");
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Người dùng không tồn tại"));
        return issueTokens(user);
    }

    public void logout(String userId) {
        // Xóa refresh token khỏi Redis — access token sẽ tự hết hạn sau 15 phút
        // ponytail: không maintain blacklist access token, 15 phút chấp nhận được
        redis.delete(REFRESH_KEY_PREFIX + userId);
    }

    public AuthResponse.UserInfo me(String userId) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Người dùng không tồn tại"));
        return new AuthResponse.UserInfo(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }

    private AuthResponse.TokenResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user.getId());
        // Lưu refresh token vào Redis với TTL
        redis.opsForValue().set(
                REFRESH_KEY_PREFIX + user.getId(),
                refreshToken,
                Duration.ofMillis(jwtService.getRefreshExpiryMs())
        );
        return new AuthResponse.TokenResponse(accessToken, refreshToken, jwtService.getAccessExpirySeconds(), "Bearer");
    }
}
