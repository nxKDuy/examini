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
        String email = normalize(req.email());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã tồn tại");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName().strip())
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

    static String normalize(String email) { return email.trim().toLowerCase(java.util.Locale.ROOT); }

    /** After this many wrong passwords for one email, sign-in pauses for {@link #LOGIN_LOCK}. */
    static final int MAX_LOGIN_FAILURES = 10;
    static final Duration LOGIN_LOCK = Duration.ofMinutes(15);
    private static final String LOGIN_FAIL_PREFIX = "login-fail:";

    @Transactional
    public AuthResponse.TokenResponse login(AuthRequest.LoginRequest req) {
        String email = normalize(req.email());
        String failures = redis.opsForValue().get(LOGIN_FAIL_PREFIX + email);
        if (failures != null && Integer.parseInt(failures) >= MAX_LOGIN_FAILURES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Đăng nhập sai quá nhiều lần. Thử lại sau 15 phút hoặc dùng Quên mật khẩu.");
        }
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !user.isPasswordSet() || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            Long n = redis.opsForValue().increment(LOGIN_FAIL_PREFIX + email);
            if (n != null && n == 1) redis.expire(LOGIN_FAIL_PREFIX + email, LOGIN_LOCK);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sai email hoặc mật khẩu");
        }
        redis.delete(LOGIN_FAIL_PREFIX + email);
        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);
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
        if (known.isPresent()) { User u = known.get(); u.setLastLoginAt(java.time.Instant.now()); userRepository.save(u); return issueTokens(u); }
        if (id.email() == null || !id.emailVerified())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email Google chưa được xác minh");
        var byEmail = userRepository.findByEmail(id.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            if (user.getGoogleSubject() != null && !user.getGoogleSubject().equals(id.subject()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã liên kết với một tài khoản Google khác");
            user.setGoogleSubject(id.subject());
            user.setLastLoginAt(java.time.Instant.now());
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
                .passwordSet(false)
                .lastLoginAt(java.time.Instant.now())
                .role(User.Role.valueOf(persona.name()))
                .personalPersona(persona)
                .systemPrivilege(User.SystemPrivilege.NONE)
                .build();
        return issueTokens(userRepository.save(user));
    }

    public AuthResponse.ProvidersResponse providers(boolean passwordReset) {
        return new AuthResponse.ProvidersResponse(google.enabled(), google.publicClientId(), passwordReset);
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

    /** Issues a new token pair; the previous refresh token of the user stops working (one session per account). */
    public AuthResponse.TokenResponse issueTokens(User user) {
        if (user.getLockedAt() != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa. Liên hệ quản trị viên.");
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
