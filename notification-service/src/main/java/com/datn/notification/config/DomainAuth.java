package com.datn.notification.config;
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

/** Every notification route belongs to the access-token subject; forwarded identity headers are never trusted. */
@Configuration
public class DomainAuth implements WebMvcConfigurer, HandlerInterceptor {
    private static final Set<String> ROLES=Set.of("STUDENT","TEACHER","ADMIN");
    @Value("${jwt.secret:}") private String jwtSecret="";
    @Override public void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(this).excludePathPatterns("/actuator/health","/error");}
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
        String bearer=req.getHeader("Authorization");
        if(Collections.list(req.getHeaders("Authorization")).size()>1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Duplicate authentication headers");
        // No internal routes: service credentials cannot act on a user's inbox.
        if(req.getHeader("X-Service-Token")!=null || bearer==null || !bearer.startsWith("Bearer ") || jwtSecret.isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Access token required");
        try {
            var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(bearer.substring(7)).getPayload();
            if(!"access".equals(claims.get("type",String.class)) || claims.getExpiration()==null || !ROLES.contains(claims.get("role",String.class)))throw new IllegalArgumentException();
            req.setAttribute("actor",UUID.fromString(claims.getSubject()));return true;
        }catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid access token");}
    }
}
