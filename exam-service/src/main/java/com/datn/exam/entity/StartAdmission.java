package com.datn.exam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="exam_start_admissions")
@Getter
@NoArgsConstructor
public class StartAdmission {
    @Id private UUID id;
    @Column(nullable=false) private UUID examId;
    @Column(nullable=false) private UUID userId;
    @Column(nullable=false) private long scheduleRevision;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private State state;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant expiresAt;
    private Instant startedAt;
    private Instant deadlineAt;
    private UUID sessionId;

    public StartAdmission(UUID id,UUID examId,UUID userId,long scheduleRevision,Instant createdAt,Instant expiresAt) {
        this.id=id;this.examId=examId;this.userId=userId;this.scheduleRevision=scheduleRevision;
        this.createdAt=createdAt;this.expiresAt=expiresAt;this.state=State.PENDING;
    }

    public void claim(Instant startedAt,Instant deadlineAt) {
        claim(startedAt,deadlineAt,expiresAt);
    }
    public void claim(Instant startedAt,Instant deadlineAt,Instant expiresAt) {
        if(state!=State.PENDING) throw new IllegalStateException("Admission is not pending");
        this.state=State.CLAIMED;this.startedAt=startedAt;this.deadlineAt=deadlineAt;this.expiresAt=expiresAt;
    }
    public void started(UUID sessionId) {
        if(state!=State.CLAIMED && state!=State.STARTED) throw new IllegalStateException("Admission is not claimed");
        this.state=State.STARTED;this.sessionId=sessionId;
    }
    public void failed() {
        if(state==State.STARTED) throw new IllegalStateException("Started admission cannot fail");
        this.state=State.FAILED;
    }
    public enum State { PENDING, CLAIMED, STARTED, FAILED }
}
