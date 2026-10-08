package com.datn.questionbank.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationAuthorizationClientTest {
    @Test void unconfiguredCredentialFailsClosedWithoutCallingProvider() {
        var client=new OrganizationAuthorizationClient("http://127.0.0.1:1","");
        var error=assertThrows(ResponseStatusException.class,()->client.require(
                UUID.randomUUID(),UUID.randomUUID(),OrganizationAuthorizationClient.Capability.BANK_USE));
        assertEquals(503,error.getStatusCode().value());
    }

    private HttpServer reply(String json) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{byte[] out=json.getBytes();x.getResponseHeaders().add("Content-Type","application/json");
            x.sendResponseHeaders(200,out.length);x.getResponseBody().write(out);x.close();});
        server.start();return server;
    }
    private int require(String json,OrganizationAuthorizationClient.Capability capability) throws Exception {
        var server=reply(json);
        try{new OrganizationAuthorizationClient("http://127.0.0.1:"+server.getAddress().getPort(),"t").require(ORG,USER,capability);return 200;}
        catch(ResponseStatusException e){return e.getStatusCode().value();}
        finally{server.stop(0);}
    }
    static final UUID ORG=UUID.randomUUID(),USER=UUID.randomUUID();
    static String decision(boolean allowed,UUID org,String orgStatus,String membership){
        return "{\"allowed\":"+allowed+",\"organizationId\":\""+org+"\",\"organizationStatus\":\""+orgStatus+"\",\"membershipStatus\":\""+membership+"\"}";}

    @Test void archivedOrganizationAllowsReadOnlyAndRevokedOrSpoofedDecisionsDeny() throws Exception {
        var read=OrganizationAuthorizationClient.Capability.BANK_READ;
        assertEquals(200,require(decision(true,ORG,"ACTIVE","ACTIVE"),OrganizationAuthorizationClient.Capability.BANK_USE));
        assertEquals(200,require(decision(true,ORG,"ARCHIVED","ACTIVE"),read));
        for(var c:OrganizationAuthorizationClient.Capability.values()) if(c!=read) assertEquals(403,require(decision(true,ORG,"ARCHIVED","ACTIVE"),c));
        assertEquals(403,require(decision(true,ORG,"ACTIVE","REVOKED"),read));
        assertEquals(403,require(decision(false,ORG,"ACTIVE","ACTIVE"),read));
        assertEquals(403,require(decision(true,UUID.randomUUID(),"ACTIVE","ACTIVE"),read));
        assertEquals(503,require("not-json",read));
    }

    @Test void membershipDiscoveryFiltersAndFailsClosed() throws Exception {
        var item=(java.util.function.BiFunction<String,String,String>)(o,m)->"{\"organizationId\":\""+UUID.randomUUID()+"\",\"organizationStatus\":\""+o+"\",\"membershipStatus\":\""+m+"\",\"role\":\"MEMBER\",\"canReview\":false,\"capabilities\":[\"BANK_READ\"]}";
        var server=reply("{\"userId\":\""+USER+"\",\"memberships\":["+item.apply("ARCHIVED","ACTIVE")+","+item.apply("ACTIVE","REVOKED")+","+item.apply("ACTIVE","ACTIVE")+"],\"truncated\":false}");
        try{
            var client=new OrganizationAuthorizationClient("http://127.0.0.1:"+server.getAddress().getPort(),"t");
            assertEquals(2,client.memberships(USER,OrganizationAuthorizationClient.Capability.BANK_READ).size());
            assertEquals(0,client.memberships(USER,OrganizationAuthorizationClient.Capability.BANK_USE).size());
            assertEquals(503,assertThrows(ResponseStatusException.class,()->client.memberships(UUID.randomUUID(),OrganizationAuthorizationClient.Capability.BANK_READ)).getStatusCode().value());
        } finally {server.stop(0);}
        assertEquals(503,assertThrows(ResponseStatusException.class,()->new OrganizationAuthorizationClient("http://127.0.0.1:1","").memberships(USER,OrganizationAuthorizationClient.Capability.BANK_READ)).getStatusCode().value());
    }
}
