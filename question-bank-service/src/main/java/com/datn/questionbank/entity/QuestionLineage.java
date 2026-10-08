package com.datn.questionbank.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
/** Immutable first-hop copy/fork origin; source versions remain retained by local FK. */
@Entity @Table(name="question_lineage")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionLineage {
    @Id @Column(name="question_id") private UUID questionId;
    @Column(name="source_question_id",nullable=false,updatable=false) private UUID sourceQuestionId;
    @Column(name="source_question_version",nullable=false,updatable=false) private long sourceQuestionVersion;
    @Column(name="source_bank_id",nullable=false,updatable=false) private UUID sourceBankId;
    @Enumerated(EnumType.STRING) @Column(name="source_bank_type",nullable=false,updatable=false,length=30) private QuestionBank.BankType sourceBankType;
    @Column(name="source_public_question_id",updatable=false) private UUID sourcePublicQuestionId;
    @Column(name="source_organization_question_id",updatable=false) private UUID sourceOrganizationQuestionId;
    @Column(name="copied_at",nullable=false,updatable=false) private LocalDateTime copiedAt;
    @Column(name="copied_by",nullable=false,updatable=false) private UUID copiedBy;
}
