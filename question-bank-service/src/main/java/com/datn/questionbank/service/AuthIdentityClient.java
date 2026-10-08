package com.datn.questionbank.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Fail-closed Auth identity resolver; only a public display name is ever used from the response. */
@Component
public class AuthIdentityClient {
    private final RestClient client;
    private final String token;

    public AuthIdentityClient(@Value("${auth.service.url:http://localhost:8081}") String url,
                              @Value("${question-bank.identity-token:}") String token) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(1500);
        requests.setReadTimeout(2500);
        this.client = RestClient.builder().baseUrl(url).requestFactory(requests).build();
        this.token = token;
    }

    public String displayName(UUID userId) {
        if (userId == null || token.isBlank()) deny();
        try {
            var identities = client.post().uri("/auth/internal/identities/resolve")
                    .header("X-Service-Token", token)
                    .body(new Request(List.of(userId)))
                    .retrieve().body(Identity[].class);
            if (identities != null) for (var identity : identities) {
                if (userId.equals(identity.userId()) && identity.displayName() != null
                        && !identity.displayName().isBlank() && !identity.displayName().contains("@"))
                    return identity.displayName().trim();
            }
        } catch (RuntimeException ignored) {
            // timeout, 401, 5xx or malformed body all deny publication
        }
        deny();
        return null;
    }

    private void deny() {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Không xác định được tên tác giả; Auth identity provider không khả dụng");
    }

    private record Request(List<UUID> userIds) {}
    /** Email is intentionally absent so it can never be copied into a publication. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Identity(UUID userId, String displayName) {}
}
