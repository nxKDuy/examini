package com.datn.grading.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="grading_audit",indexes=@Index(name="idx_grading_audit_result",columnList="result_id,created_at"))
@Getter @NoArgsConstructor
public class GradingAudit {
    @Id private UUID id;
    @Column(name="result_id",nullable=false) private UUID resultId;
    @Column(name="actor_user_id") private UUID actorUserId;
    @Column(nullable=false,length=40) private String action;
    @Column(name="result_revision",nullable=false) private long resultRevision;
    @Column(name="public_reason",columnDefinition="TEXT") private String publicReason;
    @Column(name="private_note",columnDefinition="TEXT") private String privateNote;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    public GradingAudit(UUID resultId,UUID actor,String action,long revision,String publicReason,String privateNote,Instant now){this.id=UUID.randomUUID();this.resultId=resultId;this.actorUserId=actor;this.action=action;this.resultRevision=revision;this.publicReason=publicReason;this.privateNote=privateNote;this.createdAt=now;}
}
