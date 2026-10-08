package com.datn.exam.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.List;
@FeignClient(name="exam-session-service",url="${exam-session.url:}",path="/sessions")
public interface SessionClient {
    @GetMapping("/exam/{id}/internal/eligibility") Eligibility eligibility(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/exam/{id}/internal/started") boolean started(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/exam/{id}/internal/availability") Availability availability(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/exam/{id}/internal/recipient-progress") List<com.datn.exam.dto.ScheduleDto.Progress> recipientProgress(@PathVariable UUID id,@RequestParam List<UUID> userIds,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/exam/{examId}/internal/start-admissions/{admissionId}") com.datn.exam.dto.ScheduleDto.AdmissionStatus admissionStatus(@PathVariable UUID examId,@PathVariable UUID admissionId,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/exam/{id}/internal/attempt-summary") List<AttemptSummary> attemptSummary(@PathVariable UUID id,@RequestParam List<UUID> userIds,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/exam/{id}/internal/attempts") List<Attempt> attempts(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    record AttemptSummary(UUID userId,long used,long valid,long invalidated,boolean active,UUID latestSessionId,String latestStatus,Integer latestAttemptNumber){}
    record Attempt(UUID sessionId,int attemptNumber,String status,java.time.LocalDateTime startedAt,java.time.LocalDateTime endedAt,java.time.Instant expiresAt,boolean invalidated,java.time.Instant invalidatedAt,String invalidationReason){}
    record Eligibility(long attempts,boolean resumable){}
    record Availability(long attempts,boolean resumable,java.time.Instant activeExpiresAt,long activeSessions,UUID latestSessionId,String latestSessionStatus){}
}
