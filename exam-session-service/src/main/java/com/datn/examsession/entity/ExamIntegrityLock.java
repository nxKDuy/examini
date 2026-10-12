package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** Session-local runtime gate; Exam Service remains the business authority. */
@Entity @Table(name="integrity_exam_locks")
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ExamIntegrityLock {
    @Id @Column(name="exam_id") private UUID examId;
    @Column(name="actor_user_id",nullable=false) private UUID actorUserId;
    @Column(name="locked_at",nullable=false) private Instant lockedAt;
    @Column(columnDefinition="TEXT") private String reason;
    public ExamIntegrityLock(UUID examId,UUID actorUserId,Instant lockedAt,String reason) {
        this.examId=examId;this.actorUserId=actorUserId;this.lockedAt=lockedAt;this.reason=reason;
    }
}
