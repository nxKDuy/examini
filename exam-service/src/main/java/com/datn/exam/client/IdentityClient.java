package com.datn.exam.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Auth identity resolution (contracts/identity-resolution.md). Dedicated EXAM_IDENTITY_TOKEN only; never the Notification contact token. */
@FeignClient(name="auth-service",url="${identity.auth-url:}",path="/auth/internal/identities")
public interface IdentityClient {
    @PostMapping("/resolve") List<Identity> resolve(@RequestBody Selector selector,@RequestHeader("X-Service-Token") String token);
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Selector(List<UUID> userIds,List<String> emails){}
    record Identity(UUID userId,String displayName,String email,String persona){}
}
