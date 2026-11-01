package com.datn.auth.service;

import com.datn.auth.dto.AccountDto.*;
import com.datn.auth.dto.AuthResponse;
import com.datn.auth.entity.User;
import com.datn.auth.entity.UserAvatar;
import com.datn.auth.repository.UserAvatarRepository;
import com.datn.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** The signed-in user's own account, plus the system admin's user directory. */
@Service @RequiredArgsConstructor
public class AccountService {
    static final int MAX_AVATAR_BYTES = 300 * 1024;
    static final Set<String> AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final PasswordEncoder passwords;
    private final GoogleTokenVerifier google;
    private final AuthService auth;
    private final PasswordResetService resets;
    private final Mailer mailer;
    private final StringRedisTemplate redis;

    private static ResponseStatusException error(HttpStatus s, String m) { return new ResponseStatusException(s, m); }
    private User user(UUID id) { return users.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Người dùng không tồn tại")); }
    private static String clean(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    private Profile profile(User u) {
        return new Profile(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getPersonalPersona(), u.getSystemPrivilege(),
                u.getPhone(), u.getSchool(), u.getBio(), avatars.updatedAt(u.getId()).orElse(null), u.isPasswordSet(),
                u.getGoogleSubject() != null, u.getCreatedAt(), u.getLastLoginAt());
    }

    @Transactional(readOnly = true)
    public Profile get(UUID id) { return profile(user(id)); }

    @Transactional
    public Profile update(UUID id, UpdateProfile in) {
        User u = user(id);
        u.setFullName(in.fullName().strip());
        u.setPhone(clean(in.phone())); u.setSchool(clean(in.school())); u.setBio(clean(in.bio()));
        return profile(users.save(u));
    }

    // ── Avatar ───────────────────────────────────────────────────────────────

    static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    @Transactional
    public Profile setAvatar(UUID id, byte[] data) {
        User u = user(id);
        if (data == null || data.length == 0) throw error(HttpStatus.BAD_REQUEST, "Ảnh trống");
        if (data.length > MAX_AVATAR_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Ảnh đại diện tối đa 300 KB");
        String type = imageType(data); // trust the bytes, not the header
        if (type == null) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chỉ nhận ảnh JPEG, PNG hoặc WebP");
        avatars.save(UserAvatar.builder().userId(id).content(data).contentType(type).updatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS)).build());
        avatars.flush();
        return profile(u);
    }

    @Transactional
    public Profile removeAvatar(UUID id) { User u = user(id); avatars.deleteById(id); avatars.flush(); return profile(u); }

    @Transactional(readOnly = true)
    public UserAvatar avatar(UUID id) { return avatars.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Chưa có ảnh đại diện")); }

    // ── Password and Google ──────────────────────────────────────────────────

    /** Changes (or first sets) the password; returns a new session and signs out the others. */
    @Transactional
    public AuthResponse.TokenResponse changePassword(UUID id, ChangePassword in) {
        User u = user(id);
        if (u.isPasswordSet()) {
            if (in.currentPassword() == null || !passwords.matches(in.currentPassword(), u.getPasswordHash()))
                throw error(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng");
            if (passwords.matches(in.newPassword(), u.getPasswordHash())) throw error(HttpStatus.BAD_REQUEST, "Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        u.setPasswordHash(passwords.encode(in.newPassword()));
        u.setPasswordSet(true);
        return auth.issueTokens(users.save(u));
    }

    @Transactional
    public Profile linkGoogle(UUID id, LinkGoogle in) {
        User u = user(id);
        var g = google.verify(in.credential());
        var other = users.findByGoogleSubject(g.subject());
        if (other.isPresent() && !other.get().getId().equals(id)) throw error(HttpStatus.CONFLICT, "Tài khoản Google này đã liên kết với người dùng khác");
        if (u.getGoogleSubject() != null && !u.getGoogleSubject().equals(g.subject())) throw error(HttpStatus.CONFLICT, "Đã liên kết một tài khoản Google khác. Hủy liên kết trước.");
        u.setGoogleSubject(g.subject());
        return profile(users.save(u));
    }

    @Transactional
    public Profile unlinkGoogle(UUID id) {
        User u = user(id);
        if (!u.isPasswordSet()) throw error(HttpStatus.CONFLICT, "Hãy đặt mật khẩu trước khi hủy liên kết Google, nếu không bạn sẽ không đăng nhập được");
        u.setGoogleSubject(null);
        return profile(users.save(u));
    }

    // ── System admin ─────────────────────────────────────────────────────────

    private User admin(UUID actor) {
        User a = user(actor);
        if (a.getSystemPrivilege() != User.SystemPrivilege.ADMIN) throw error(HttpStatus.FORBIDDEN, "Chỉ quản trị viên hệ thống");
        return a;
    }
    private static AdminUser adminView(User u) {
        return new AdminUser(u.getId(), u.getEmail(), u.getFullName(), u.getRole(), u.getPersonalPersona(), u.getSystemPrivilege(),
                u.getLockedAt() != null, u.getLockedAt(), u.getGoogleSubject() != null, u.isPasswordSet(), u.getCreatedAt(), u.getLastLoginAt());
    }

    @Transactional(readOnly = true)
    public AdminPage list(UUID actor, String q, int page, int size) {
        admin(actor);
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (query.length() > 100) throw error(HttpStatus.BAD_REQUEST, "Từ khóa quá dài");
        var result = users.search(query, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("email"))));
        return new AdminPage(result.getContent().stream().map(AccountService::adminView).toList(), result.getTotalElements(), result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @Transactional
    public AdminUser setLocked(UUID actor, UUID target, boolean locked) {
        admin(actor);
        if (actor.equals(target)) throw error(HttpStatus.BAD_REQUEST, "Không thể tự khóa tài khoản của mình");
        User u = user(target);
        if (locked && u.getSystemPrivilege() == User.SystemPrivilege.ADMIN) throw error(HttpStatus.BAD_REQUEST, "Không thể khóa quản trị viên khác");
        if (locked && u.getLockedAt() == null) { u.setLockedAt(Instant.now().truncatedTo(ChronoUnit.MICROS)); redis.delete("refresh:" + target); }
        if (!locked) u.setLockedAt(null);
        return adminView(users.save(u));
    }

    /** A reset link the admin can hand over (and that is emailed when SMTP is set up). */
    @Transactional
    public ResetLink resetLink(UUID actor, UUID target) {
        admin(actor);
        User u = user(target);
        if (u.getLockedAt() != null) throw error(HttpStatus.CONFLICT, "Mở khóa tài khoản trước khi đặt lại mật khẩu");
        var link = resets.create(u);
        boolean emailed = mailer.send(u.getEmail(), "EXAMINI - Đặt lại mật khẩu", """
                Xin chào %s,

                Quản trị viên đã tạo liên kết đặt lại mật khẩu cho tài khoản EXAMINI của bạn (hiệu lực 30 phút):

                %s
                """.formatted(u.getFullName(), link.url()));
        return new ResetLink(link.url(), link.expiresAt(), emailed);
    }
}
