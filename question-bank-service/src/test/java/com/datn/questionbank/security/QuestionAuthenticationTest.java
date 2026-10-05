package com.datn.questionbank.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionAuthenticationTest {
    static final String JWT_TEST_KEY = "test-only-jwt-key-32-bytes-or-longer-never-production";
    static final String SERVICE_TEST_KEY = "isolated-test-service-token";
    UUID owner = UUID.randomUUID();
    QuestionAuthentication filter = new QuestionAuthentication(JWT_TEST_KEY, SERVICE_TEST_KEY, new ObjectMapper());
    String token(String type, String role, Date expiry) {
        return Jwts.builder().subject(owner.toString()).claim("type",type).claim("role",role).expiration(expiry)
                .signWith(Keys.hmacShaKeyFor(JWT_TEST_KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    MockHttpServletRequest request(String method, String path) {
        var req=new MockHttpServletRequest(method,path); req.setServletPath(path); return req;
    }
    MockHttpServletResponse run(QuestionAuthentication filter, MockHttpServletRequest req) throws Exception {
        var response=new MockHttpServletResponse(); filter.doFilter(req,response,(r,s)->s.getWriter().write("OK")); return response;
    }
    @Test void validAccessUsesSignedIdentityIgnoringSpoofedHeaders() throws Exception {
        var req=request("GET","/questions");req.addHeader("Authorization","Bearer "+token("access","TEACHER",new Date(System.currentTimeMillis()+60000)));
        req.addHeader("X-User-Id",UUID.randomUUID().toString());req.addHeader("X-User-Role","ADMIN");
        assertEquals("OK",run(filter,req).getContentAsString());
        assertEquals(new QuestionActor(owner,"TEACHER",false),req.getAttribute(QuestionActor.class.getName()));
    }
    @Test void spoofedHeadersAnonymousRefreshExpiredInvalidAndUnknownRoleFailClosed() throws Exception {
        var headers=request("GET","/questions");headers.addHeader("X-User-Id",owner.toString());headers.addHeader("X-User-Role","ADMIN");
        assertEquals(401,run(filter,headers).getStatus());
        for(String jwt:List.of(token("refresh","ADMIN",new Date(System.currentTimeMillis()+60000)),
                token("access","ADMIN",new Date(1)),token("access","SUPERUSER",new Date(System.currentTimeMillis()+60000)),"invalid")){
            var req=request("GET","/questions");req.addHeader("Authorization","Bearer "+jwt);assertEquals(401,run(filter,req).getStatus());
        }
    }
    @Test void missingExpiryAndNonUuidSubjectRejected() throws Exception {
        for(String jwt:List.of(Jwts.builder().subject(owner.toString()).claim("type","access").claim("role","ADMIN")
                .signWith(Keys.hmacShaKeyFor(JWT_TEST_KEY.getBytes(StandardCharsets.UTF_8))).compact(),
                Jwts.builder().subject("not-a-uuid").claim("type","access").claim("role","ADMIN").expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(JWT_TEST_KEY.getBytes(StandardCharsets.UTF_8))).compact())) {
            var req=request("GET","/questions");req.addHeader("Authorization","Bearer "+jwt);assertEquals(401,run(filter,req).getStatus());
        }
    }
    MockHttpServletRequest internal(String method,String path) {
        var req=request(method,path);req.addHeader("X-Service-Token",SERVICE_TEST_KEY);req.addHeader("X-Question-Owner-Id",owner.toString());return req;
    }
    @Test void serviceRequiresConfiguredCredentialOwnerAndCannotMixUserAuth() throws Exception {
        var disabled=new QuestionAuthentication(JWT_TEST_KEY,"",new ObjectMapper());
        assertEquals(401,run(disabled,internal("POST","/questions/batch")).getStatus());
        var invalid=internal("POST","/questions/batch");invalid.removeHeader("X-Service-Token");invalid.addHeader("X-Service-Token","wrong");
        assertEquals(401,run(filter,invalid).getStatus());
        var missing=internal("POST","/questions/batch");missing.removeHeader("X-Question-Owner-Id");assertEquals(401,run(filter,missing).getStatus());
        var mixed=internal("POST","/questions/batch");mixed.addHeader("Authorization","Bearer "+token("access","ADMIN",new Date(System.currentTimeMillis()+60000)));
        assertEquals(401,run(filter,mixed).getStatus());
        var duplicates=internal("GET","/questions/random");duplicates.addHeader("X-Question-Owner-Id",UUID.randomUUID().toString());
        assertEquals(401,run(filter,duplicates).getStatus());
    }
    @Test void internalLaneReadOnlyAndEligibilityCannotBeBypassed() throws Exception {
        for(String path:List.of("/questions","/questions/"+UUID.randomUUID()+"/review","/questions/import/csv"))
            assertEquals(403,run(filter,internal("POST",path)).getStatus());
        assertEquals(403,run(filter,internal("GET","/questions/"+UUID.randomUUID()+"/versions")).getStatus());
        var unsafe=internal("POST","/questions/batch");unsafe.addParameter("eligible","false");assertEquals(403,run(filter,unsafe).getStatus());
        var valid=internal("POST","/questions/batch");assertEquals("OK",run(filter,valid).getContentAsString());
        assertEquals(new QuestionActor(owner,"SERVICE",true),valid.getAttribute(QuestionActor.class.getName()));
        assertEquals("OK",run(filter,internal("GET","/questions/random")).getContentAsString());
    }
    @Test void taxonomyMutationsRequireAdminAndEmptyJwtConfigurationFailsClosed() throws Exception {
        var req=request("DELETE","/categories/"+UUID.randomUUID());req.addHeader("Authorization","Bearer "+token("access","TEACHER",new Date(System.currentTimeMillis()+60000)));
        assertEquals(403,run(filter,req).getStatus());
        var noKey=new QuestionAuthentication("",SERVICE_TEST_KEY,new ObjectMapper());
        var access=request("GET","/questions");access.addHeader("Authorization","Bearer "+token("access","ADMIN",new Date(System.currentTimeMillis()+60000)));
        assertEquals(401,run(noKey,access).getStatus());
    }
}
