package com.datn.questionbank.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
@Entity @Table(name="public_contributions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class PublicContribution {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="source_question_id",nullable=false,updatable=false) private UUID sourceQuestionId;
    @Column(name="source_question_version",nullable=false,updatable=false) private long sourceQuestionVersion;
    @Column(name="source_bank_id",nullable=false,updatable=false) private UUID sourceBankId;
    @Column(name="source_owner_user_id",nullable=false,updatable=false) private UUID sourceOwnerUserId;
    @Column(name="submitted_by",nullable=false,updatable=false) private UUID submittedBy;
    @Column(name="submitted_at",nullable=false,updatable=false) private LocalDateTime submittedAt;
    @Column(nullable=false,updatable=false,columnDefinition="TEXT") private String snapshot;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private VerificationStatus status;
    @Column(name="reviewer_id") private UUID reviewerId;
    @Column(name="review_note",length=5000) private String reviewNote;
    @Column(name="reviewed_at") private LocalDateTime reviewedAt;
    @Version @Column(name="lock_version",nullable=false) private long lockVersion;
    public enum VerificationStatus { PUBLIC_PENDING, PUBLIC_VERIFIED, PUBLIC_REJECTED, PUBLIC_PUBLISHED }
}
