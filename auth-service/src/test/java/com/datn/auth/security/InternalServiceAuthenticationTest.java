package com.datn.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class InternalServiceAuthenticationTest {

    private MockHttpServletResponse call(String configured, String path, String supplied) throws Exception {
        var filter = new InternalServiceAuthentication(configured, new ObjectMapper());
        var request = new MockHttpServletRequest("POST", path);
        request.setContent("{\"userIds\":[]}".getBytes());
        if (supplied != null) request.addHeader("X-Service-Token", supplied);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) assertNotNull(chain.getRequest());
        else assertNull(chain.getRequest());
        return response;
    }

    @Test
    void failsClosedWhenTokenIsNotConfigured() throws Exception {
        assertEquals(401, call("", "/auth/internal/contacts", "").getStatus());
        assertEquals(401, call("", "/auth/internal/contacts", null).getStatus());
        assertEquals(401, call("   ", "/auth/internal/contacts", "   ").getStatus());
    }

    @Test
    void rejectionDoesNotEchoCredential() throws Exception {
        var response = call("test-only-configured", "/auth/internal/contacts", "test-only-supplied-secret");
        assertEquals(401, response.getStatus());
        assertFalse(response.getContentAsString().contains("test-only"));
    }

    @Test
    void protectsWholeInternalPrefixOnly() throws Exception {
        assertEquals(401, call("test-only-configured", "/auth/internal/other", null).getStatus());
        assertEquals(401, call("test-only-configured", "/auth/internal", null).getStatus());
        assertEquals(200, call("test-only-configured", "/auth/login", null).getStatus());
        assertEquals(200, call("test-only-configured", "/auth/internal/contacts", "test-only-configured").getStatus());
    }
}
