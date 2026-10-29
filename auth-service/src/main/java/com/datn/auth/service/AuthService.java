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
    private final GoogleTokenVerifier google;

    private static final String REFRESH_KEY_PREFIX = "refresh:";

    @Transactional
    public AuthResponse.TokenResponse register(AuthRequest.RegisterRequest req) {
        if (req.accountType() == AuthRequest.RegisterRequest.AccountType.BUSINESS) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Đăng ký tổ chức chưa được hỗ trợ");
        }
        if (req.persona() != null && req.role() != null
                && !req.persona().name().equals(req.role().name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "persona và role không khớp");
        }
        User.Persona persona = resolvePersona(req);
        if (userRepository.existsByEmail(req.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã tồn tại");
        }
        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .role(User.Role.valueOf(persona.name()))
                .personalPersona(persona)
                .systemPrivilege(User.SystemPrivilege.NONE)
                .build();
        userRepository.save(user);
        return issueTokens(user);
    }

    private User.Persona resolvePersona(AuthRequest.RegisterRequest req) {
        if (req.persona() != null) return User.Persona.valueOf(req.persona().name());
        if (req.role() == null) return User.Persona.STUDENT;
        if (req.role() == User.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không thể đăng ký tài khoản quản trị");
        }
        return User.Persona.valueOf(req.role().name());
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

    /**
     * Google Sign-In. Known Google account → sign in. Existing account with the same verified email → link and sign in.
     * Otherwise a new personal account is created with the chosen persona (409 PERSONA_REQUIRED asks the client to choose).
     * Google accounts get an unusable random password; privileged accounts are never created here.
     */
    @Transactional
    public AuthResponse.TokenResponse google(AuthRequest.GoogleRequest req) {
        var id = google.verify(req.credential());
        var known = userRepository.findByGoogleSubject(id.subject());
        if (known.isPresent()) return issueTokens(known.get());
        if (id.email() == null || !id.emailVerified())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email Google chưa được xác minh");
        var byEmail = userRepository.findByEmail(id.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            if (user.getGoogleSubject() != null && !user.getGoogleSubject().equals(id.subject()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã liên kết với một tài khoản Google khác");
            user.setGoogleSubject(id.subject());
            return issueTokens(userRepository.save(user));
        }
        if (req.persona() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "PERSONA_REQUIRED");
        User.Persona persona = User.Persona.valueOf(req.persona().name());
        String name = id.name() == null || id.name().isBlank() ? id.email().substring(0, id.email().indexOf('@')) : id.name().strip();
        User user = User.builder()
                .email(id.email())
                .passwordHash(passwordEncoder.encode(UUID.randomUUID() + ":" + UUID.randomUUID()))
                .fullName(name.length() > 255 ? name.substring(0, 255) : name)
                .googleSubject(id.subject())
                .role(User.Role.valueOf(persona.name()))
                .personalPersona(persona)
                .systemPrivilege(User.SystemPrivilege.NONE)
                .build();
        return issueTokens(userRepository.save(user));
    }

    public AuthResponse.ProvidersResponse providers() {
        return new AuthResponse.ProvidersResponse(google.enabled(), google.publicClientId());
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

    public String authenticatedUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Thiếu access token");
        }
        try {
            Claims claims = jwtService.validateAndExtract(authorization.substring(7));
            if (!"access".equals(claims.get("type", String.class))) throw new JwtException("Not an access token");
            return claims.getSubject();
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token không hợp lệ");
        }
    }

    public AuthResponse.UserInfo me(String userId) {
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Người dùng không tồn tại"));
        return new AuthResponse.UserInfo(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getPersonalPersona(), user.getSystemPrivilege());
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
