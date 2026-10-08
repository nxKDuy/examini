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
    @Column(name="question_id") private UUID questionId;
    @Column(name="old_score") private Float oldScore;
    @Column(name="new_score") private Float newScore;
    @Column(name="old_feedback",columnDefinition="TEXT") private String oldFeedback;
    @Column(name="new_feedback",columnDefinition="TEXT") private String newFeedback;
    @Column(columnDefinition="TEXT") private String reason;
    @Column(name="public_reason",columnDefinition="TEXT") private String publicReason;
    @Column(name="private_note",columnDefinition="TEXT") private String privateNote;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    public GradingAudit(UUID resultId,UUID actor,String action,long revision,String publicReason,String privateNote,Instant now){this.id=UUID.randomUUID();this.resultId=resultId;this.actorUserId=actor;this.action=action;this.resultRevision=revision;this.publicReason=publicReason;this.privateNote=privateNote;this.createdAt=now;}
    public GradingAudit(UUID resultId,UUID actor,String action,long revision,UUID questionId,Float oldScore,Float newScore,String oldFeedback,String newFeedback,String reason,Instant now){
        this(resultId,actor,action,revision,null,null,now);this.questionId=questionId;this.oldScore=oldScore;this.newScore=newScore;this.oldFeedback=oldFeedback;this.newFeedback=newFeedback;this.reason=reason;
    }
}
