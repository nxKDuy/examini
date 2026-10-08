package com.datn.auth.service;

import com.datn.auth.dto.IdentityDto;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdentityResolutionServiceTest {
    private final UserRepository repo = mock(UserRepository.class);
    private final IdentityResolutionService service = new IdentityResolutionService(repo);

    private User user(UUID id, String email, User.Persona persona) {
        return User.builder().id(id).email(email).fullName("N " + email).passwordHash("test-only-hash")
                .role(User.Role.STUDENT).personalPersona(persona).systemPrivilege(User.SystemPrivilege.NONE).build();
    }

    @Test
    void userIdsDedupKeepRequestOrderOmitMissingOneBatch() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), missing = UUID.randomUUID();
        when(repo.findAllById(any())).thenReturn(List.of(user(b, "b@example.test", User.Persona.TEACHER),
                user(a, "a@example.test", User.Persona.STUDENT)));
        var out = service.resolve(new IdentityDto.ResolveRequest(List.of(b, missing, a, b), null));
        assertEquals(List.of(b, a), out.stream().map(IdentityDto.Identity::userId).toList());
        assertEquals("TEACHER", out.get(0).persona());
        assertEquals("N b@example.test", out.get(0).displayName());
        verify(repo, times(1)).findAllById(any());
        verifyNoMoreInteractions(repo);
    }

    @Test
    void emailsAreExactDedupedAndOrdered() {
        when(repo.findByEmailIn(any())).thenReturn(List.of(user(UUID.randomUUID(), "b@example.test", null),
                user(UUID.randomUUID(), "a@example.test", User.Persona.STUDENT)));
        var out = service.resolve(new IdentityDto.ResolveRequest(null,
                List.of("b@example.test", "B@example.test", "a@example.test", "b@example.test")));
        assertEquals(List.of("b@example.test", "a@example.test"), out.stream().map(IdentityDto.Identity::email).toList());
        assertNull(out.get(0).persona());
        verify(repo, times(1)).findByEmailIn(any());
    }
}
