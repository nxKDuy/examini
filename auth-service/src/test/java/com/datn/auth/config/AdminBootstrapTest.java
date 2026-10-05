package com.datn.auth.config;

import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBootstrapTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);

    @Test void productionWithoutAnActiveAdminRequiresConfiguration() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> new AdminBootstrap(users,passwords,env,"","").run(null));
        verify(users,never()).save(any());
    }

    @Test void weakOrPartialConfigurationFails() {
        assertThrows(IllegalStateException.class, () -> new AdminBootstrap(users,passwords,new MockEnvironment(),"admin@example.test","short").run(null));
        verify(users,never()).save(any());
    }

    @Test void configuredBootstrapCreatesTrustedAdmin() {
        when(users.findByEmail("admin@example.test")).thenReturn(Optional.empty());
        when(passwords.encode("test-only-long-password")).thenReturn("encoded");
        new AdminBootstrap(users,passwords,new MockEnvironment(),"ADMIN@example.test","test-only-long-password").run(null);
        var capture = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(capture.capture());
        assertEquals(User.Role.ADMIN,capture.getValue().getRole());
        assertEquals(User.SystemPrivilege.ADMIN,capture.getValue().getSystemPrivilege());
        assertNull(capture.getValue().getPersonalPersona());
        assertEquals("encoded",capture.getValue().getPasswordHash());
    }

    @Test void bootstrapCannotPromoteAnExistingPersonalAccount() {
        when(users.findByEmail("teacher@example.test")).thenReturn(Optional.of(User.builder()
                .role(User.Role.TEACHER).systemPrivilege(User.SystemPrivilege.NONE).build()));
        assertThrows(IllegalStateException.class, () -> new AdminBootstrap(users,passwords,new MockEnvironment(),"teacher@example.test","test-only-long-password").run(null));
        verify(users,never()).save(any());
    }
}
