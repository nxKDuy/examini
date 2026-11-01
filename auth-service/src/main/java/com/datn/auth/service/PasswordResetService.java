package com.datn.auth.service;

import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * One-time password reset links (30 minutes, only the newest link of a user works). Redis keeps a hash of the
 * token, never the token. The forgot endpoint answers the same for unknown emails so it cannot probe accounts.
 */
@Service @Slf4j
public class PasswordResetService {
    static final Duration TTL = Duration.ofMinutes(30);
    static final int MAX_REQUESTS_PER_HOUR = 3;
    private static final String TOKEN = "pwreset:", USER = "pwreset-user:", RATE = "pwreset-rate:";
    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final Mailer mailer;
    private final String baseUrl;
    private final boolean logLinks;

    public PasswordResetService(UserRepository users, PasswordEncoder passwords, StringRedisTemplate redis, Mailer mailer,
                                @Value("${auth.password-reset.base-url:http://localhost:3000}") String baseUrl,
                                @Value("${auth.password-reset.log-links:false}") boolean logLinks) {
        this.users = users; this.passwords = passwords; this.redis = redis; this.mailer = mailer;
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.logLinks = logLinks;
    }

    /** Self-service reset is offered only when the link can reach the user (email, or the dev log). */
    public boolean available() { return mailer.configured() || logLinks; }

    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public record Link(String url, Instant expiresAt) {}

    /** Creates a link for the user, replacing any earlier one. */
    public Link create(User user) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String previous = redis.opsForValue().get(USER + user.getId());
        if (previous != null) redis.delete(TOKEN + previous);
        String h = hash(token);
        redis.opsForValue().set(TOKEN + h, user.getId().toString(), TTL);
        redis.opsForValue().set(USER + user.getId(), h, TTL);
        return new Link(baseUrl + "/reset-password?token=" + token, Instant.now().plus(TTL));
    }

    /** Forgot password: always "accepted"; sends a link when the email belongs to an active account. */
    public void forgot(String rawEmail) {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chức năng quên mật khẩu chưa được cấu hình");
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        Long count = redis.opsForValue().increment(RATE + email);
        if (count != null && count == 1) redis.expire(RATE + email, Duration.ofHours(1));
        if (count != null && count > MAX_REQUESTS_PER_HOUR) return;
        users.findByEmail(email).filter(u -> u.getLockedAt() == null).ifPresent(u -> {
            Link link = create(u);
            boolean sent = mailer.send(u.getEmail(), "EXAMINI - Đặt lại mật khẩu", """
                    Xin chào %s,

                    Có yêu cầu đặt lại mật khẩu cho tài khoản EXAMINI của bạn. Mở liên kết dưới đây trong 30 phút để đặt mật khẩu mới:

                    %s

                    Nếu bạn không yêu cầu, hãy bỏ qua email này; mật khẩu hiện tại vẫn giữ nguyên.
                    """.formatted(u.getFullName(), link.url()));
            if (!sent && logLinks) log.info("Password reset link for {} (dev log, SMTP off): {}", u.getEmail(), link.url());
        });
    }

    /** Sets the new password, burns the link and signs out every session of the user. */
    @Transactional
    public void reset(String token, String newPassword) {
        String h = hash(token == null ? "" : token);
        String userId = redis.opsForValue().get(TOKEN + h);
        if (userId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn");
        User user = users.findById(UUID.fromString(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn"));
        if (user.getLockedAt() != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa");
        user.setPasswordHash(passwords.encode(newPassword));
        user.setPasswordSet(true);
        users.save(user);
        redis.delete(TOKEN + h);
        redis.delete(USER + userId);
        redis.delete("refresh:" + userId);
    }
}
