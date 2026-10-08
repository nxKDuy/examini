package com.datn.organization.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provider-owned trust boundary. Any path with an "internal" segment accepts only the dedicated Question Bank service
 * credential (no Bearer, X-User-* ignored); everything else accepts only a verified access JWT (no service token).
 * Client-supplied identity headers are never read.
 */
@Component
public class RequestAuthentication extends OncePerRequestFilter {
    public static final String ACTOR = Actor.class.getName();
    static final int MAX_INTERNAL_BODY = 8 * 1024;

    private final String jwtSecret;
    private final byte[] qbToken;
    private final ObjectMapper json;

    public RequestAuthentication(@Value("${jwt.secret:}") String jwtSecret,
                                 @Value("${organization.internal.qb-token:}") String qbToken, ObjectMapper json) {
        this.jwtSecret = jwtSecret;
        this.qbToken = qbToken.isBlank() ? null : qbToken.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return request.getMethod().equals("GET") && (path.equals("/actuator/health") || path.equals("/actuator/info"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpServletRequest forward = request;
        try {
            if (isInternal(request)) {
                authenticateService(request);
                forward = bounded(request);
            } else {
                request.setAttribute(ACTOR, authenticateUser(request));
            }
        } catch (ResponseStatusException error) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason());
            response.setStatus(error.getStatusCode().value());
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/problem+json");
            json.writeValue(response.getOutputStream(), pd);
            return;
        }
        chain.doFilter(forward, response);
    }

    private static String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }

    static boolean isInternal(HttpServletRequest request) {
        return Arrays.stream(path(request).toLowerCase().split("/")).anyMatch("internal"::equals);
    }

    private void authenticateService(HttpServletRequest request) {
        List<String> authorization = Collections.list(request.getHeaders("Authorization"));
        List<String> service = Collections.list(request.getHeaders("X-Service-Token"));
        if (qbToken == null || !authorization.isEmpty() || service.size() != 1
                || !MessageDigest.isEqual(qbToken, service.getFirst().getBytes(StandardCharsets.UTF_8))) throw unauthorized();
    }

    private Actor authenticateUser(HttpServletRequest request) {
        List<String> authorization = Collections.list(request.getHeaders("Authorization"));
        if (authorization.size() != 1 || !authorization.getFirst().startsWith("Bearer ") || jwtSecret.isBlank()
                || request.getHeaders("X-Service-Token").hasMoreElements()) throw unauthorized();
        try {
            Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(authorization.getFirst().substring(7)).getPayload();
            String role = claims.get("role", String.class);
            if (!"access".equals(claims.get("type", String.class)) || claims.getExpiration() == null
                    || !Set.of("ADMIN", "TEACHER", "STUDENT").contains(role == null ? "" : role)) throw unauthorized();
            return new Actor(UUID.fromString(claims.getSubject()), role);
        } catch (JwtException | IllegalArgumentException | NullPointerException error) {
            throw unauthorized();
        }
    }

    private HttpServletRequest bounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_INTERNAL_BODY) throw tooLarge();
        byte[] body = request.getInputStream().readNBytes(MAX_INTERNAL_BODY + 1);
        if (body.length > MAX_INTERNAL_BODY) throw tooLarge();
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return in.read(); }
                    @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                    @Override public boolean isFinished() { return in.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
        };
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Xác thực không hợp lệ");
    }

    private static ResponseStatusException tooLarge() {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Yêu cầu quá lớn");
    }
}
