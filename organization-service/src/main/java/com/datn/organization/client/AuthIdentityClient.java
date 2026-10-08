package com.datn.organization.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Calls Auth's internal resolver directly on the service network with the dedicated ORGANIZATION_IDENTITY_TOKEN. */
@Component
public class AuthIdentityClient implements IdentityResolver {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;

    public AuthIdentityClient(ObjectMapper json, @Value("${organization.auth.base-url}") String baseUrl,
                              @Value("${organization.auth.identity-token:}") String token) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    @Override public List<Identity> byUserIds(Collection<UUID> userIds) {
        return userIds.isEmpty() ? List.of() : call(Map.of("userIds", userIds));
    }

    @Override public List<Identity> byEmails(Collection<String> emails) {
        return emails.isEmpty() ? List.of() : call(Map.of("emails", emails));
    }

    private List<Identity> call(Map<String, Object> selector) {
        if (token.isBlank()) throw new Unavailable("ORGANIZATION_IDENTITY_TOKEN chưa cấu hình", null);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/internal/identities/resolve"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").header("X-Service-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(selector))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new Unavailable("Auth identity provider trả " + response.statusCode(), null);
            return json.readValue(response.body(), new TypeReference<List<Identity>>() {});
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Auth identity provider bị gián đoạn", interrupted);
        } catch (Unavailable unavailable) {
            throw unavailable;
        } catch (Exception failure) {
            throw new Unavailable("Auth identity provider không khả dụng", failure);
        }
    }
}
