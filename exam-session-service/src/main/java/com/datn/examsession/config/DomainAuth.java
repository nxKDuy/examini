package com.datn.examsession.config;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class DomainAuth implements WebMvcConfigurer, HandlerInterceptor {
    @Value("${jwt.secret:}") private String jwtSecret="";
    @Value("${exam.internal-token:}") private String internalToken="";
    @Override public void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(this).excludePathPatterns("/actuator/health","/error");}
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
        String bearer=req.getHeader("Authorization"),service=req.getHeader("X-Service-Token");
        if(java.util.Collections.list(req.getHeaders("Authorization")).size()>1 || java.util.Collections.list(req.getHeaders("X-Service-Token")).size()>1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Duplicate authentication headers");
        // Authorize the mapped controller route, not the raw/percent-encoded URL.
        String mapped=String.valueOf(req.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if(isScheduleInternal(mapped)) {
            if(bearer!=null || service==null || internalToken.isBlank() || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),service.getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Internal service authentication required");
            return true;
        }
        boolean internal=mapped.matches("/exams/[^/]+/internal/(start|safe|grading|permission)|/sessions/[^/]+/internal/(answers|owner)|/sessions/exam/[^/]+/internal/eligibility");
        if(internal) {
            if(bearer!=null || service==null || internalToken.isBlank() || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),service.getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Internal service authentication required");
            return true;
        }
        if(service!=null || bearer==null || !bearer.startsWith("Bearer ") || jwtSecret.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Access token required");
        try {
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(bearer.substring(7)).getPayload();
            String role=claims.get("role",String.class);
            if(!"access".equals(claims.get("type",String.class)) || claims.getExpiration()==null || !Set.of("TEACHER","ADMIN","STUDENT").contains(role)) throw new IllegalArgumentException();
            if((mapped.endsWith("/monitor") || mapped.endsWith("/attempts") || mapped.endsWith("/invalidate")) && "STUDENT".equals(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Teacher role required");
            req.setAttribute("actor",UUID.fromString(claims.getSubject()));req.setAttribute("actorRole",role);return true;
        }catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid access token");}
    }
    private boolean isScheduleInternal(String path) {
        return path.matches("/sessions/exam/[^/]+/internal/(started|availability|recipient-progress|start-admissions/[^/]+|attempt-summary|attempts|integrity-lock|integrity-unlock)");
    }
}
