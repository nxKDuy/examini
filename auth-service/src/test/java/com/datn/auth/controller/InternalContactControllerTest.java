package com.datn.auth.controller;

import com.datn.auth.config.SecurityConfig;
import com.datn.auth.dto.ContactDto;
import com.datn.auth.entity.User;
import com.datn.auth.security.InternalServiceAuthentication;
import com.datn.auth.service.ContactService;
import com.datn.auth.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = InternalContactController.class,
        properties = "auth.internal.notification-contact-token=test-only-notification-contact-token")
@Import({SecurityConfig.class, InternalServiceAuthentication.class})
class InternalContactControllerTest {
    private static final String TOKEN = "test-only-notification-contact-token";
    private static final String JWT_SECRET = "a-secure-secret-that-is-at-least-32-bytes-long";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;
    @MockBean ContactService contactService;

    private MockHttpServletRequestBuilder contacts(String body) {
        return post("/auth/internal/contacts").contentType("application/json").content(body);
    }

    private String ids(UUID... ids) {
        return "{\"userIds\":[" + java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
    }

    private ResultActions expectRejected(MockHttpServletRequestBuilder request) throws Exception {
        ResultActions result = mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(contactService);
        return result;
    }

    @Test
    void validServiceTokenReturnsOnlyMinimalContactFields() throws Exception {
        when(contactService.contacts(List.of(USER)))
                .thenReturn(List.of(new ContactDto.Contact(USER, "student@example.com", "Student One")));

        mvc.perform(contacts(ids(USER)).header("X-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].userId").value(USER.toString()))
                .andExpect(jsonPath("$[0].email").value("student@example.com"))
                .andExpect(jsonPath("$[0].displayName").value("Student One"));
    }

    @Test
    void missingServiceTokenIsRejected() throws Exception {
        expectRejected(contacts(ids(USER)));
    }

    @Test
    void wrongServiceTokenIsRejected() throws Exception {
        expectRejected(contacts(ids(USER)).header("X-Service-Token", TOKEN + "x"));
        expectRejected(contacts(ids(USER)).header("X-Service-Token", TOKEN.substring(1)));
    }

    @Test
    void emptyServiceTokenIsRejected() throws Exception {
        expectRejected(contacts(ids(USER)).header("X-Service-Token", ""));
    }

    @Test
    void duplicatedServiceTokenHeaderIsRejected() throws Exception {
        expectRejected(contacts(ids(USER)).header("X-Service-Token", TOKEN, TOKEN));
    }

    @Test
    void ordinaryUserAndAdminBearerTokensDoNotReplaceServiceToken() throws Exception {
        JwtService jwt = new JwtService(JWT_SECRET, 60_000, 120_000);
        User admin = User.builder().id(UUID.randomUUID()).email("admin@example.com").role(User.Role.ADMIN)
                .systemPrivilege(User.SystemPrivilege.ADMIN).build();
        User teacher = User.builder().id(UUID.randomUUID()).email("teacher@example.com").role(User.Role.TEACHER)
                .personalPersona(User.Persona.TEACHER).systemPrivilege(User.SystemPrivilege.NONE).build();

        for (String token : List.of(jwt.generateAccessToken(admin), jwt.generateRefreshToken(admin.getId()),
                jwt.generateAccessToken(teacher))) {
            expectRejected(contacts(ids(USER)).header("Authorization", "Bearer " + token));
            // Kèm Bearer cũng bị từ chối: không chấp nhận credential nhập nhằng.
            expectRejected(contacts(ids(USER)).header("Authorization", "Bearer " + token)
                    .header("X-Service-Token", TOKEN));
        }
        expectRejected(contacts(ids(USER)).header("Authorization", "Bearer " + TOKEN));
    }

    @Test
    void spoofedIdentityHeadersAreIgnored() throws Exception {
        expectRejected(contacts(ids(USER))
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "ADMIN")
                .header("X-User-System-Privilege", "ADMIN")
                .header("X-Service-Name", "notification-service"));
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeBodyValidation() throws Exception {
        expectRejected(contacts("{\"userIds\":[\"not-a-uuid\"]}"));
    }

    @Test
    void invalidUuidIsBadRequest() throws Exception {
        mvc.perform(contacts("{\"userIds\":[\"not-a-uuid\"]}").header("X-Service-Token", TOKEN))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(contactService);
    }

    @Test
    void missingEmptyOrNullUserIdsAreBadRequest() throws Exception {
        for (String body : List.of("{}", "{\"userIds\":null}", "{\"userIds\":[]}", "{\"userIds\":[null]}")) {
            mvc.perform(contacts(body).header("X-Service-Token", TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors").exists());
        }
        verifyNoInteractions(contactService);
    }

    @Test
    void batchLimitIsOneHundredIds() throws Exception {
        when(contactService.contacts(any())).thenReturn(List.of());
        UUID[] hundred = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        mvc.perform(contacts(ids(hundred)).header("X-Service-Token", TOKEN))
                .andExpect(status().isOk());

        UUID[] tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        mvc.perform(contacts(ids(tooMany)).header("X-Service-Token", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userIds").exists());
        verify(contactService, times(1)).contacts(any());
    }

    @Test
    void oversizedBodyIsRejectedBeforeParsing() throws Exception {
        String padding = " ".repeat(17 * 1024);
        mvc.perform(contacts("{\"userIds\":[\"" + USER + "\"]" + padding + "}").header("X-Service-Token", TOKEN))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(contactService);
    }

    @Test
    void nonexistentIdsYieldEmptyList() throws Exception {
        when(contactService.contacts(List.of(USER))).thenReturn(List.of());

        mvc.perform(contacts(ids(USER)).header("X-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
    }
}
