package com.datn.exam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Immutable record of one extra-attempt grant to one Student; (examId,userId,requestId) makes retries idempotent. */
@Entity @Table(name="exam_attempt_grants") @Getter @NoArgsConstructor
public class AttemptGrant {
    @Id private UUID id;
    @Column(name="exam_id",nullable=false) private UUID examId;
    @Column(name="user_id",nullable=false) private UUID userId;
    @Column(nullable=false) private int quantity;
    @Column(nullable=false,columnDefinition="TEXT") private String reason;
    @Column(name="actor_user_id",nullable=false) private UUID actorUserId;
    @Column(name="request_id",nullable=false,length=100) private String requestId;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    public AttemptGrant(UUID examId,UUID userId,int quantity,String reason,UUID actor,String requestId,Instant now) {
        this.id=UUID.randomUUID();this.examId=examId;this.userId=userId;this.quantity=quantity;this.reason=reason;this.actorUserId=actor;this.requestId=requestId;this.createdAt=now;
    }
}
