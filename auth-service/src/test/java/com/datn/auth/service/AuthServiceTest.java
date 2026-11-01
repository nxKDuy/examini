package com.datn.auth.service;

import com.datn.auth.dto.AuthRequest;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

class AuthServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final JwtService jwt = mock(JwtService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, passwords, jwt, redis, mock(GoogleTokenVerifier.class));
        when(redis.opsForValue()).thenReturn(values);
        lenient().when(users.existsByEmail(any())).thenReturn(false);
        lenient().when(passwords.encode(any())).thenReturn("encoded");
        lenient().when(jwt.generateAccessToken(any())).thenReturn("access");
        lenient().when(jwt.generateRefreshToken(any())).thenReturn("refresh");
        lenient().when(jwt.getRefreshExpiryMs()).thenReturn(60_000L);
        lenient().when(jwt.getAccessExpirySeconds()).thenReturn(60L);
    }

    @Test
    void registrationDefaultsLegacyRequestsToPersonalStudent() {
        service.register(new AuthRequest.RegisterRequest("s@example.com", "password", "Student", null, null, null));
        var user = capturedUser();
        assertEquals(User.Role.STUDENT, user.getRole());
        assertEquals(User.Persona.STUDENT, user.getPersonalPersona());
        assertEquals(User.SystemPrivilege.NONE, user.getSystemPrivilege());
    }

    @Test
    void personalTeacherCanSelfRegisterWithoutApproval() {
        service.register(new AuthRequest.RegisterRequest("t@example.com", "password", "Teacher",
                AuthRequest.RegisterRequest.AccountType.PERSONAL,
                AuthRequest.RegisterRequest.Persona.TEACHER, null));
        var user = capturedUser();
        assertEquals(User.Role.TEACHER, user.getRole());
        assertEquals(User.Persona.TEACHER, user.getPersonalPersona());
        assertEquals(User.SystemPrivilege.NONE, user.getSystemPrivilege());
    }

    @Test
    void existingTeacherAccountCanStillLogin() {
        var existing = User.builder().id(UUID.randomUUID()).email("teacher@example.com")
                .fullName("Teacher").passwordHash("encoded").role(User.Role.TEACHER)
                .personalPersona(User.Persona.TEACHER).systemPrivilege(User.SystemPrivilege.NONE).build();
        when(users.findByEmail("teacher@example.com")).thenReturn(java.util.Optional.of(existing));
        when(passwords.matches("password", "encoded")).thenReturn(true);

        var tokens = service.login(new AuthRequest.LoginRequest("teacher@example.com", "password"));

        assertEquals("Bearer", tokens.tokenType());
        verify(jwt).generateAccessToken(existing);
    }

    @Test
    void existingStudentAccountCanStillLogin() {
        var existing = User.builder().id(UUID.randomUUID()).email("student@example.com")
                .fullName("Student").passwordHash("encoded").role(User.Role.STUDENT)
                .personalPersona(User.Persona.STUDENT).systemPrivilege(User.SystemPrivilege.NONE).build();
        when(users.findByEmail("student@example.com")).thenReturn(java.util.Optional.of(existing));
        when(passwords.matches("password", "encoded")).thenReturn(true);

        assertEquals("Bearer", service.login(new AuthRequest.LoginRequest("student@example.com", "password")).tokenType());
        verify(jwt).generateAccessToken(existing);
    }

    @Test
    void legacyAdminRoleRegistrationIsRejected() {
        var error = assertThrows(ResponseStatusException.class, () -> service.register(
                new AuthRequest.RegisterRequest("a@example.com", "password", "Attacker", null, null, User.Role.ADMIN)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(users, never()).save(any());
    }

    @Test
    void conflictingPersonaAndLegacyRoleAreRejected() {
        var error = assertThrows(ResponseStatusException.class, () -> service.register(
                new AuthRequest.RegisterRequest("x@example.com", "password", "User", null,
                        AuthRequest.RegisterRequest.Persona.TEACHER, User.Role.STUDENT)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(users, never()).save(any());
    }

    @Test
    void businessRegistrationIsExplicitlyDeferred() {
        var error = assertThrows(ResponseStatusException.class, () -> service.register(
                new AuthRequest.RegisterRequest("b@example.com", "password", "Business",
                        AuthRequest.RegisterRequest.AccountType.BUSINESS, null, null)));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, error.getStatusCode());
        verify(users, never()).save(any());
    }

    @Test
    void currentUserIdentityComesFromAccessTokenSubject() {
        var claims = mock(Claims.class);
        when(jwt.validateAndExtract("signed")).thenReturn(claims);
        when(claims.get("type", String.class)).thenReturn("access");
        when(claims.getSubject()).thenReturn("user-id-from-token");
        assertEquals("user-id-from-token", service.authenticatedUserId("Bearer signed"));
    }

    @Test
    void refreshTokenCannotAuthenticateCurrentUser() {
        var claims = mock(Claims.class);
        when(jwt.validateAndExtract("signed")).thenReturn(claims);
        when(claims.get("type", String.class)).thenReturn("refresh");
        assertThrows(ResponseStatusException.class, () -> service.authenticatedUserId("Bearer signed"));
    }

    @Test
    void arbitraryPrivilegeFieldsCannotBindIntoRegistrationDto() throws Exception {
        var request = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue("""
                {"email":"x@example.com","password":"password","fullName":"User",
                 "accountType":"PERSONAL","persona":"STUDENT","permissions":["*"],
                 "roles":["TEACHER","ADMIN"],"user":{"role":"ADMIN"},"systemPrivilege":"ADMIN"}
                """, AuthRequest.RegisterRequest.class);
        service.register(request);
        var user = capturedUser();
        assertEquals(User.Role.STUDENT, user.getRole());
        assertEquals(User.SystemPrivilege.NONE, user.getSystemPrivilege());
    }

    @Test
    void systemAdminPersonaCannotBeDeserializedAsPublicPersona() {
        assertThrows(com.fasterxml.jackson.databind.exc.InvalidFormatException.class, () ->
                new ObjectMapper().readValue("""
                        {"email":"x@example.com","password":"password","fullName":"User","persona":"SYSTEM_ADMIN"}
                        """, AuthRequest.RegisterRequest.class));
    }

    private User capturedUser() {
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        return captor.getValue();
    }
}
