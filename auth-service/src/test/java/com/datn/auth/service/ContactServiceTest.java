package com.datn.auth.service;

import com.datn.auth.dto.ContactDto;
import com.datn.auth.entity.User;
import com.datn.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContactServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ContactService service = new ContactService(users);

    @Test
    @SuppressWarnings("unchecked")
    void singleBatchLookupKeepsRequestOrderDropsDuplicatesAndUnknownIds() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), missing = UUID.randomUUID();
        User userA = User.builder().id(a).email("a@example.com").fullName("A").passwordHash("hash")
                .role(User.Role.ADMIN).systemPrivilege(User.SystemPrivilege.ADMIN).build();
        User userB = User.builder().id(b).email("b@example.com").fullName("B").passwordHash("hash")
                .role(User.Role.STUDENT).personalPersona(User.Persona.STUDENT).build();
        when(users.findAllById(any())).thenReturn(List.of(userA, userB));

        var result = service.contacts(List.of(b, missing, a, b));

        assertEquals(List.of(new ContactDto.Contact(b, "b@example.com", "B"),
                new ContactDto.Contact(a, "a@example.com", "A")), result);
        verify(users, times(1)).findAllById(argThat(ids -> Set.copyOf((java.util.Collection<UUID>) ids)
                .equals(Set.of(a, b, missing)) && ((java.util.Collection<UUID>) ids).size() == 3));
        verifyNoMoreInteractions(users);
    }
}
