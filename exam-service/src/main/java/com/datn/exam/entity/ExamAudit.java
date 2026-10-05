package com.datn.exam.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="exam_audit") @Getter @NoArgsConstructor
public class ExamAudit {
    @Id private UUID id;
    @Column(nullable=false) private UUID examId;
    @Column(nullable=false) private UUID actorUserId;
    @Column(nullable=false) private String action;
    @Column(nullable=false) private Instant timestamp;
    @Column(columnDefinition="TEXT") private String metadata;
    public ExamAudit(UUID exam, UUID actor, String action, String metadata) {
        this.id=UUID.randomUUID(); this.examId=exam; this.actorUserId=actor;
        this.action=action; this.metadata=metadata; this.timestamp=Instant.now();
    }
}
