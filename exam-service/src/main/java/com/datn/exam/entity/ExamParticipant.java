package com.datn.exam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user registered for an Exam. Revocation is a state, never a delete, so assignment history survives.
 * Follow (see Exam.followers) is a different relation: it neither registers nor consumes capacity or attempts.
 */
@Entity @Table(name="exam_participants") @IdClass(ExamParticipant.Key.class) @Getter @NoArgsConstructor
public class ExamParticipant {
    public enum Status { ACTIVE, REVOKED }
    @Id @Column(name="exam_id") private UUID examId;
    @Id @Column(name="user_id") private UUID userId;
    /** ASSIGNED (added by staff) or the AccessMode through which the user joined (JOIN_CODE / PUBLIC). */
    @Column(nullable=false,length=30) private String source;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private Status status=Status.ACTIVE;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    /** Staff member who assigned the participant; null for self-service Join. */
    @Column(name="added_by") private UUID addedBy;
    @Column(name="status_changed_at") private Instant statusChangedAt;
    @Column(name="status_changed_by") private UUID statusChangedBy;
    /** Public reason shown to the Student; private notes are never stored here. */
    @Column(name="revocation_reason",columnDefinition="TEXT") private String revocationReason;
    /** Sum of positive per-Student attempt grants; the Exam attemptLimit stays untouched. */
    @Column(name="extra_attempts",nullable=false) private int extraAttempts;

    public ExamParticipant(UUID examId,UUID userId,String source,UUID addedBy,Instant now) {
        this.examId=examId;this.userId=userId;this.source=source;this.addedBy=addedBy;this.createdAt=now;
    }
    public boolean isActive(){return status==Status.ACTIVE;}
    public void revoke(UUID actor,Instant now,String reason){status=Status.REVOKED;statusChangedAt=now;statusChangedBy=actor;revocationReason=reason;}
    public void reinstate(UUID actor,Instant now){status=Status.ACTIVE;statusChangedAt=now;statusChangedBy=actor;revocationReason=null;}
    public void addExtraAttempts(int quantity){extraAttempts=Math.addExact(extraAttempts,quantity);}

    public static class Key implements Serializable {
        private UUID examId;private UUID userId;
        public Key(){}
        public Key(UUID examId,UUID userId){this.examId=examId;this.userId=userId;}
        @Override public boolean equals(Object o){return o instanceof Key k&&Objects.equals(examId,k.examId)&&Objects.equals(userId,k.userId);}
        @Override public int hashCode(){return Objects.hash(examId,userId);}
    }
}
