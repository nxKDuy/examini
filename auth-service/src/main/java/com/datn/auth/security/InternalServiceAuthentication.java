package com.datn.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UrlPathHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/**
 * Trust boundary cho /auth/internal/**: chỉ chấp nhận service credential riêng của Notification.
 * Bearer token người dùng hay X-User-* header không bao giờ thay thế được credential này.
 */
@Component
public class InternalServiceAuthentication extends OncePerRequestFilter {

    static final String INTERNAL_PREFIX = "/auth/internal/";
    static final int MAX_BODY_BYTES = 16 * 1024;   // 100 UUID JSON ~4 KiB, chừa dư cho định dạng

    private final byte[] contactToken;
    private final ObjectMapper json;

    public InternalServiceAuthentication(@Value("${auth.internal.notification-contact-token:}") String contactToken,
                                         ObjectMapper json) {
        this.contactToken = contactToken.isBlank() ? null : contactToken.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(path.startsWith(INTERNAL_PREFIX) || path.equals("/auth/internal"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest bounded;
        try {
            authenticate(request);
            bounded = boundedBody(request);
        } catch (ResponseStatusException error) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason());
            pd.setType(URI.create("about:blank"));
            response.setStatus(error.getStatusCode().value());
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/problem+json");
            json.writeValue(response.getOutputStream(), pd);
            return;
        }
        chain.doFilter(bounded, response);
    }

    private void authenticate(HttpServletRequest request) {
        List<String> authorization = Collections.list(request.getHeaders("Authorization"));
        List<String> service = Collections.list(request.getHeaders("X-Service-Token"));
        if (contactToken == null || !authorization.isEmpty() || service.size() != 1
                || !MessageDigest.isEqual(contactToken, service.getFirst().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Xác thực không hợp lệ");
        }
    }

    private HttpServletRequest boundedBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw tooLarge();
        return new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return in.read(); }
                    @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                    @Override public boolean isFinished() { return in.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }

            @Override
            public int getContentLength() { return body.length; }

            @Override
            public long getContentLengthLong() { return body.length; }
        };
    }

    private ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Yêu cầu quá lớn");
    }
}
