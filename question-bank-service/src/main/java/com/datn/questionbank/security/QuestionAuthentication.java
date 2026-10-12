package com.datn.questionbank.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UrlPathHelper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Provider-owned trust boundary: no identity/role is taken from unverified X-User-* headers. */
@Component
public class QuestionAuthentication extends OncePerRequestFilter {
    private final String jwtSecret;
    private final String internalToken;
    private final ObjectMapper json;
    public QuestionAuthentication(@Value("${jwt.secret:}") String jwtSecret,
            @Value("${question-bank.internal-token:}") String internalToken, ObjectMapper json) {
        this.jwtSecret = jwtSecret; this.internalToken = internalToken; this.json = json;
    }
    private String path(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return request.getMethod().equals("GET") && (path.equals("/actuator/health") || path.equals("/actuator/info")
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        QuestionActor actor;
        try {
            String auth = singleHeader(request, "Authorization"), service = singleHeader(request, "X-Service-Token");
            if (auth != null && service != null) throw unauthorized();
            actor = service != null ? serviceActor(request, service) : userActor(auth);
            String path = path(request);
            if ((path.startsWith("/categories") || path.startsWith("/subjects"))
                    && !Set.of("GET", "HEAD").contains(request.getMethod()) && !actor.admin())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ ADMIN quản lý môn học/danh mục");
        } catch (ResponseStatusException error) {
            response.setStatus(error.getStatusCode().value()); response.setCharacterEncoding("UTF-8"); response.setContentType("application/problem+json");
            json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason()));
            return;
        }
        request.setAttribute(QuestionActor.class.getName(), actor);
        chain.doFilter(request, response);
    }
    private QuestionActor userActor(String auth) {
        if (auth == null || !auth.startsWith("Bearer ") || jwtSecret.isBlank()) throw unauthorized();
        try {
            Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build().parseSignedClaims(auth.substring(7)).getPayload();
            String role = claims.get("role", String.class);
            if (!"access".equals(claims.get("type", String.class)) || claims.getExpiration() == null
                    || !Set.of("ADMIN", "TEACHER", "STUDENT").contains(role == null ? "" : role)) throw unauthorized();
            String privilege = claims.get("systemPrivilege", String.class);
            if (privilege != null && (!Set.of("NONE", "ADMIN").contains(privilege)
                    || "ADMIN".equals(privilege) != "ADMIN".equals(role))) throw unauthorized();
            return new QuestionActor(UUID.fromString(claims.getSubject()), role, false, privilege == null ? "NONE" : privilege);
        } catch (JwtException | IllegalArgumentException | NullPointerException error) { throw unauthorized(); }
    }
    private QuestionActor serviceActor(HttpServletRequest request, String supplied) {
        if (internalToken.isBlank() || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw unauthorized();
        boolean random = request.getMethod().equals("GET") && path(request).equals("/questions/random");
        boolean batch = request.getMethod().equals("POST") && path(request).equals("/questions/batch");
        boolean pick = request.getMethod().equals("POST") && path(request).equals("/questions/eligible/random");
        if (!random && !batch && !pick) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service chỉ được chọn/đọc batch đủ điều kiện");
        String[] eligibility = request.getParameterValues("eligible");
        if (batch && eligibility != null && (eligibility.length != 1 || !"true".equalsIgnoreCase(eligibility[0])))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service không được bỏ kiểm tra eligibility");
        try { return new QuestionActor(UUID.fromString(singleHeader(request, "X-Question-Owner-Id")), "SERVICE", true); }
        catch (IllegalArgumentException | NullPointerException error) { throw unauthorized(); }
    }
    private String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() > 1) throw unauthorized();
        return values.isEmpty() ? null : values.getFirst();
    }
    private ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Xác thực không hợp lệ"); }
}
