package com.datn.exam.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Participant & Attempt management contracts. Revocation reason / grant reason / invalidation reason are PUBLIC (Student-visible). */
public final class ParticipantDto {
    private ParticipantDto() {}

    /** Exactly one of userIds / emails, 1..100 values. */
    public record AddRequest(List<UUID> userIds,List<String> emails) {}
    public record RevokeRequest(String reason) {}
    public record GrantRequest(Integer quantity,String reason,String requestId) {}
    public record Counts(long registered,long eligible,long revoked) {}

    public record AddResult(String input,UUID userId,String displayName,String outcome) {}
    public record AddResponse(List<AddResult> results,Counts counts) {}

    public record AttemptInfo(long used,long valid,long invalidated,long remaining,boolean active,
                              UUID latestSessionId,Integer latestAttemptNumber,String latestStatus) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(UUID userId,String displayName,String email,String source,String status,Instant joinedAt,UUID addedBy,
                      Instant statusChangedAt,UUID statusChangedBy,String revocationReason,
                      int extraAttempts,int attemptLimit,int effectiveAttemptLimit,AttemptInfo attempts) {}

    public record Roster(List<Row> content,int page,int size,long totalElements,int totalPages,Counts counts) {}

    public record GrantView(UUID grantId,int quantity,String reason,Instant createdAt) {}
    public record GrantResponse(UUID userId,int extraAttempts,int effectiveAttemptLimit,GrantView grant,boolean replayed) {}
    public record GrantHistory(UUID id,int quantity,String reason,UUID actorUserId,Instant createdAt) {}

    public record AttemptView(UUID sessionId,int attemptNumber,String status,java.time.LocalDateTime startedAt,java.time.LocalDateTime endedAt,
                              Instant expiresAt,boolean invalidated,Instant invalidatedAt,String invalidationReason) {}

    /** Student's own participation; never contains other users or private notes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mine(UUID examId,String status,String source,Instant joinedAt,Instant revokedAt,String revocationReason,
                       int attemptLimit,int extraAttempts,int effectiveAttemptLimit,long used,long remaining,boolean activeAttempt,
                       boolean followed,List<AttemptView> attempts,List<GrantView> grants) {}
}
