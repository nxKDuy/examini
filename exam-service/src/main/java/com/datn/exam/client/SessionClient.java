package com.datn.exam.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@FeignClient(name="exam-session-service",url="${exam-session.url:}",path="/sessions")
public interface SessionClient {
    @GetMapping("/exam/{id}/internal/eligibility") Eligibility eligibility(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    record Eligibility(long attempts,boolean resumable){}
}
