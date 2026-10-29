package com.datn.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies a Google Sign-In ID token (Google Identity Services "credential"): Google's signature (cached public
 * keys), audience = one of our client ids, issuer accounts.google.com, not expired. Blank client id = disabled.
 */
@Component
public class GoogleTokenVerifier {
    public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {}

    private final List<String> clientIds;
    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenVerifier(@Value("${auth.google.client-ids:}") String clientIds) {
        this.clientIds = Arrays.stream(clientIds.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.verifier = this.clientIds.isEmpty() ? null
                : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance()).setAudience(this.clientIds).build();
    }

    public boolean enabled() { return verifier != null; }
    /** The first configured client id, for the browser button (public, not a secret). */
    public String publicClientId() { return clientIds.isEmpty() ? null : clientIds.getFirst(); }

    public GoogleIdentity verify(String credential) {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Đăng nhập Google chưa được cấu hình");
        if (credential == null || credential.isBlank() || credential.length() > 4096) throw invalid();
        GoogleIdToken token;
        try { token = verifier.verify(credential); }
        catch (Exception e) { throw invalid(); }
        if (token == null) throw invalid();
        var p = token.getPayload();
        return new GoogleIdentity(p.getSubject(), p.getEmail() == null ? null : p.getEmail().trim().toLowerCase(),
                Boolean.TRUE.equals(p.getEmailVerified()), (String) p.get("name"));
    }

    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập Google không hợp lệ hoặc đã hết hạn"); }
}
