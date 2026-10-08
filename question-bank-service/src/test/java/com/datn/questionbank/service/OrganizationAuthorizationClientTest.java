package com.datn.questionbank.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationAuthorizationClientTest {
    @Test void unconfiguredCredentialFailsClosedWithoutCallingProvider() {
        var client=new OrganizationAuthorizationClient("http://127.0.0.1:1","");
        var error=assertThrows(ResponseStatusException.class,()->client.require(
                UUID.randomUUID(),UUID.randomUUID(),OrganizationAuthorizationClient.Capability.BANK_USE));
        assertEquals(503,error.getStatusCode().value());
    }
}
