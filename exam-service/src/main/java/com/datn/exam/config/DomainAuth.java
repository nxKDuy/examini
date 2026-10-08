package com.datn.exam.config;
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
    @Value("${notification.internal-token:}") private String notificationToken="";
    @Value("${analytics.permission-token:}") private String analyticsPermissionToken="";
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
        boolean notificationInternal="GET".equals(req.getMethod()) && mapped.equals("/exams/{id}/internal/notification-recipients");
        if(notificationInternal) {
            if(bearer!=null || service==null || notificationToken.isBlank() || !MessageDigest.isEqual(notificationToken.getBytes(StandardCharsets.UTF_8),service.getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Notification service authentication required");
            return true;
        }
        boolean analyticsPermission="GET".equals(req.getMethod()) && mapped.equals("/exams/{id}/internal/permission") && "true".equals(req.getParameter("manage"));
        if(analyticsPermission && bearer==null && service!=null && !analyticsPermissionToken.isBlank() && MessageDigest.isEqual(analyticsPermissionToken.getBytes(StandardCharsets.UTF_8),service.getBytes(StandardCharsets.UTF_8)))return true;
        boolean internal=mapped.matches("/exams/[^/]+/internal/(start|safe|grading|permission|result-policy)|/sessions/[^/]+/internal/(answers|owner)|/sessions/exam/[^/]+/internal/eligibility");
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
            if("STUDENT".equals(role) && isScheduleStudentRoute(req.getMethod(),mapped)) {
                req.setAttribute("actor",UUID.fromString(claims.getSubject()));req.setAttribute("actorRole",role);return true;
            }
            String path=mapped;
            boolean studentPath=path.equals("/exams/public") || path.equals("/exams/my") || path.endsWith("/lobby") || path.endsWith("/preflight") || path.endsWith("/join") || path.startsWith("/exams/code/");
            if(!studentPath && "STUDENT".equals(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Teacher role required");
            req.setAttribute("actor",UUID.fromString(claims.getSubject()));req.setAttribute("actorRole",role);return true;
        }catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid access token");}
    }
    private boolean isScheduleInternal(String path) {
        return path.matches("/exams/[^/]+/internal/start-admissions(?:/[^/]+/(?:claim|confirm|release))?|/exams/[^/]+/internal/attempt-limit|/sessions/exam/[^/]+/internal/(started|availability|recipient-progress|start-admissions/[^/]+)");
    }
    private boolean isScheduleStudentRoute(String method,String path) {
        return ("GET".equals(method) && path.equals("/exams/calendar")) || path.matches("/exams/[^/]+/(availability|follow|participation/me)") || path.endsWith("/preview");
    }
}
