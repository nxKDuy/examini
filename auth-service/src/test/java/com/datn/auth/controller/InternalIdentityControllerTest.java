package com.datn.auth.controller;

import com.datn.auth.config.SecurityConfig;
import com.datn.auth.dto.IdentityDto;
import com.datn.auth.security.InternalServiceAuthentication;
import com.datn.auth.service.ContactService;
import com.datn.auth.service.IdentityResolutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = {InternalIdentityController.class, InternalContactController.class}, properties = {
        "auth.internal.notification-contact-token=test-only-contact-token",
        "auth.internal.organization-identity-token=test-only-org-identity-token",
        "auth.internal.exam-identity-token=test-only-exam-identity-token"})
@Import({SecurityConfig.class, InternalServiceAuthentication.class})
class InternalIdentityControllerTest {
    private static final String ORG = "test-only-org-identity-token";
    private static final String EXAM = "test-only-exam-identity-token";
    private static final String CONTACT = "test-only-contact-token";
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mvc;
    @MockBean IdentityResolutionService identities;
    @MockBean ContactService contacts;

    private MockHttpServletRequestBuilder resolve(String body) {
        return post("/auth/internal/identities/resolve").contentType("application/json").content(body);
    }

    private String ids(UUID... ids) {
        return "{\"userIds\":[" + java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"")
                .collect(Collectors.joining(",")) + "]}";
    }

    private void rejected(MockHttpServletRequestBuilder r) throws Exception {
        String body = mvc.perform(r).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("test-only"));
        verifyNoInteractions(identities);
    }

    @Test
    void bothConsumerCredentialsReturnOnlyIdentityFields() throws Exception {
        when(identities.resolve(any())).thenReturn(
                List.of(new IdentityDto.Identity(USER, "Student One", "s@example.test", "STUDENT")));
        for (String token : List.of(ORG, EXAM)) {
            mvc.perform(resolve(ids(USER)).header("X-Service-Token", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].length()").value(4))
                    .andExpect(jsonPath("$[0].userId").value(USER.toString()))
                    .andExpect(jsonPath("$[0].displayName").value("Student One"))
                    .andExpect(jsonPath("$[0].email").value("s@example.test"))
                    .andExpect(jsonPath("$[0].persona").value("STUDENT"));
        }
    }

    @Test
    void emailSelectorAccepted() throws Exception {
        when(identities.resolve(any())).thenReturn(List.of());
        mvc.perform(resolve("{\"emails\":[\"a@example.test\"]}").header("X-Service-Token", ORG))
                .andExpect(status().isOk()).andExpect(content().json("[]", true));
    }

    @Test
    void noOtherCredentialIsAccepted() throws Exception {
        rejected(resolve(ids(USER)));
        rejected(resolve(ids(USER)).header("X-Service-Token", ""));
        rejected(resolve(ids(USER)).header("X-Service-Token", ORG + "x"));
        rejected(resolve(ids(USER)).header("X-Service-Token", CONTACT));
        rejected(resolve(ids(USER)).header("X-Service-Token", ORG, EXAM));
        rejected(resolve(ids(USER)).header("X-Service-Token", ORG).header("Authorization", "Bearer " + ORG));
        rejected(resolve(ids(USER)).header("Authorization", "Bearer " + ORG));
        rejected(resolve(ids(USER)).header("X-User-Id", USER.toString()).header("X-User-Role", "ADMIN"));
    }

    @Test
    void identityCredentialsDoNotOpenContactsRoute() throws Exception {
        for (String token : List.of(ORG, EXAM)) {
            mvc.perform(post("/auth/internal/contacts").contentType("application/json")
                    .content(ids(USER)).header("X-Service-Token", token)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(contacts);
    }

    @Test
    void invalidSelectorsAreBadRequestWithoutEchoingInput() throws Exception {
        List<String> bodies = List.of("{}", "{\"userIds\":null}", "{\"userIds\":[]}", "{\"emails\":[]}",
                "{\"userIds\":[null]}", "{\"emails\":[\"\"]}", "{\"emails\":[null]}", "{\"userIds\":[\"not-a-uuid\"]}",
                "{\"userIds\":[\"" + USER + "\"],\"emails\":[\"secret-pii@example.test\"]}",
                "{\"emails\":[\"" + "x".repeat(256) + "\"]}", "{\"emails\":\"secret-pii@example.test\"}",
                "not json secret-pii@example.test", "");
        for (String body : bodies) {
            String out = mvc.perform(resolve(body).header("X-Service-Token", ORG))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertFalse(out.contains("secret-pii"));
        }
        verifyNoInteractions(identities);
    }

    @Test
    void batchLimitAndBodyLimit() throws Exception {
        when(identities.resolve(any())).thenReturn(List.of());
        UUID[] hundred = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        mvc.perform(resolve(ids(hundred)).header("X-Service-Token", EXAM)).andExpect(status().isOk());
        UUID[] many = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toArray(UUID[]::new);
        mvc.perform(resolve(ids(many)).header("X-Service-Token", EXAM)).andExpect(status().isBadRequest());
        mvc.perform(resolve(ids(USER) + " ".repeat(17 * 1024)).header("X-Service-Token", EXAM))
                .andExpect(status().isPayloadTooLarge());
        verify(identities, times(1)).resolve(any());
    }
}
