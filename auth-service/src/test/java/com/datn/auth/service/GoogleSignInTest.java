package com.datn.auth.service;

import com.datn.auth.dto.AuthRequest;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoogleSignInTest {
    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder passwords = mock(PasswordEncoder.class);
    final JwtService jwt = mock(JwtService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final GoogleTokenVerifier google = mock(GoogleTokenVerifier.class);
    AuthService service;
    static final GoogleTokenVerifier.GoogleIdentity ID = new GoogleTokenVerifier.GoogleIdentity("g-123", "hs@gmail.com", true, "Nguyễn Văn A");

    @BeforeEach void setUp() {
        service = new AuthService(users, passwords, jwt, redis, google);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(passwords.encode(any())).thenReturn("random-hash");
        when(jwt.generateAccessToken(any())).thenReturn("access");
        when(jwt.generateRefreshToken(any())).thenReturn("refresh");
        when(jwt.getRefreshExpiryMs()).thenReturn(60_000L);
        when(users.save(any())).thenAnswer(c -> { User u = c.getArgument(0); if (u.getId() == null) u.setId(UUID.randomUUID()); return u; });
        when(google.verify("cred")).thenReturn(ID);
        when(users.findByGoogleSubject(any())).thenReturn(Optional.empty());
        when(users.findByEmail(any())).thenReturn(Optional.empty());
    }
    int status(Runnable r) { return assertThrows(ResponseStatusException.class, r::run).getStatusCode().value(); }

    @Test void newGoogleUserMustChooseAPersonaThenGetsAPersonalAccount() {
        var e = assertThrows(ResponseStatusException.class, () -> service.google(new AuthRequest.GoogleRequest("cred", null)));
        assertEquals(409, e.getStatusCode().value()); assertEquals("PERSONA_REQUIRED", e.getReason());
        assertEquals("access", service.google(new AuthRequest.GoogleRequest("cred", AuthRequest.RegisterRequest.Persona.TEACHER)).accessToken());
        var saved = ArgumentCaptor.forClass(User.class); verify(users).save(saved.capture());
        var u = saved.getValue();
        assertEquals("hs@gmail.com", u.getEmail()); assertEquals("g-123", u.getGoogleSubject()); assertEquals("Nguyễn Văn A", u.getFullName());
        assertEquals(User.Persona.TEACHER, u.getPersonalPersona()); assertEquals(User.SystemPrivilege.NONE, u.getSystemPrivilege());
        assertEquals("random-hash", u.getPasswordHash(), "no usable password for Google accounts");
    }

    @Test void knownGoogleAccountSignsInAndExistingEmailIsLinkedOnce() {
        var existing = User.builder().id(UUID.randomUUID()).email("hs@gmail.com").passwordHash("h").fullName("A").role(User.Role.STUDENT)
                .personalPersona(User.Persona.STUDENT).systemPrivilege(User.SystemPrivilege.NONE).build();
        when(users.findByEmail("hs@gmail.com")).thenReturn(Optional.of(existing));
        service.google(new AuthRequest.GoogleRequest("cred", null));
        assertEquals("g-123", existing.getGoogleSubject(), "linked on first Google sign-in");
        when(users.findByGoogleSubject("g-123")).thenReturn(Optional.of(existing));
        service.google(new AuthRequest.GoogleRequest("cred", AuthRequest.RegisterRequest.Persona.TEACHER));
        assertEquals(User.Persona.STUDENT, existing.getPersonalPersona(), "persona of an existing account never changes");
        verify(users, times(2)).save(any()); // the link, then the last sign-in time
        assertNotNull(existing.getLastLoginAt());
    }

    @Test void unverifiedEmailOrAnotherLinkedGoogleAccountIsRefused() {
        when(google.verify("cred")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-9", "x@gmail.com", false, null));
        assertEquals(403, status(() -> service.google(new AuthRequest.GoogleRequest("cred", AuthRequest.RegisterRequest.Persona.STUDENT))));
        when(google.verify("cred")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-9", "x@gmail.com", true, null));
        var other = User.builder().email("x@gmail.com").googleSubject("g-OTHER").build();
        when(users.findByEmail("x@gmail.com")).thenReturn(Optional.of(other));
        assertEquals(409, status(() -> service.google(new AuthRequest.GoogleRequest("cred", null))));
    }

    @Test void verifierIsOffWithoutClientIdAndRejectsGarbage() {
        var off = new GoogleTokenVerifier("");
        assertFalse(off.enabled()); assertNull(off.publicClientId());
        assertEquals(503, status(() -> off.verify("x")));
        var on = new GoogleTokenVerifier("abc.apps.googleusercontent.com, def.apps.googleusercontent.com");
        assertTrue(on.enabled()); assertEquals("abc.apps.googleusercontent.com", on.publicClientId());
        assertEquals(401, status(() -> on.verify("not-a-jwt")));
        assertEquals(401, status(() -> on.verify(" ")));
    }
}
