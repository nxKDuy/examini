package com.datn.questionbank.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuthIdentityClientTest {
    HttpServer server;
    final AtomicReference<String> token=new AtomicReference<>();
    final AtomicReference<String> body=new AtomicReference<>();
    volatile int status=200;
    volatile String response="[]";

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/auth/internal/identities/resolve",x->{
            token.set(x.getRequestHeaders().getFirst("X-Service-Token"));
            body.set(new String(x.getRequestBody().readAllBytes()));
            byte[] out=response.getBytes();x.getResponseHeaders().add("Content-Type","application/json");
            x.sendResponseHeaders(status,out.length);x.getResponseBody().write(out);x.close();});
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void denied(AuthIdentityClient c,UUID id){assertEquals(503,assertThrows(ResponseStatusException.class,()->c.displayName(id)).getStatusCode().value());}

    @Test void resolvesDisplayNameWithDedicatedTokenAndIgnoresEmail(){
        var id=UUID.randomUUID();
        response="[{\"userId\":\""+id+"\",\"displayName\":\" Cô Lan \",\"email\":\"lan@example.test\",\"persona\":\"TEACHER\"}]";
        assertEquals("Cô Lan",new AuthIdentityClient(url(),"qb-secret").displayName(id));
        assertEquals("qb-secret",token.get());assertTrue(body.get().contains(id.toString()));
    }
    @Test void missingTokenAuthFailureMissingIdentitySpoofAndEmailNameFailClosed(){
        var id=UUID.randomUUID();
        denied(new AuthIdentityClient(url(),""),id);assertNull(token.get());
        status=401;denied(new AuthIdentityClient(url(),"x"),id);
        status=200;response="[]";denied(new AuthIdentityClient(url(),"x"),id);
        response="[{\"userId\":\""+UUID.randomUUID()+"\",\"displayName\":\"Other\"}]";denied(new AuthIdentityClient(url(),"x"),id);
        response="[{\"userId\":\""+id+"\",\"displayName\":\"lan@example.test\"}]";denied(new AuthIdentityClient(url(),"x"),id);
        response="not-json";denied(new AuthIdentityClient(url(),"x"),id);
        server.stop(0);denied(new AuthIdentityClient(url(),"x"),id);
    }
}
