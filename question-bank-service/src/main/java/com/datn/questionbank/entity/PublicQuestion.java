package com.datn.questionbank.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;
/** Immutable content publication; only its discovery lifecycle may be archived. */
@Entity @Table(name="public_questions")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PublicQuestion {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="bank_id",nullable=false,updatable=false) private UUID bankId;
    @Column(name="contribution_id",updatable=false,unique=true) private UUID contributionId;
    @Column(name="source_question_id",nullable=false,updatable=false) private UUID sourceQuestionId;
    @Column(name="source_question_version",nullable=false,updatable=false) private long sourceQuestionVersion;
    @Column(name="source_bank_id",nullable=false,updatable=false) private UUID sourceBankId;
    @Column(name="source_owner_user_id",nullable=false,updatable=false) private UUID sourceOwnerUserId;
    @Column(name="source_owner_display_name",nullable=false,updatable=false,length=200) private String sourceOwnerDisplayName;
    @Column(nullable=false,updatable=false,columnDefinition="TEXT") private String snapshot;
    @Column(name="search_content",nullable=false,updatable=false,columnDefinition="TEXT") private String searchContent;
    @Column(name="question_type",nullable=false,updatable=false,length=30) @Enumerated(EnumType.STRING) private Question.QuestionType questionType;
    @Column(name="difficulty_level",nullable=false,updatable=false,length=10) @Enumerated(EnumType.STRING) private Question.DifficultyLevel difficultyLevel;
    @Column(name="verified_by",nullable=false,updatable=false) private UUID verifiedBy;
    @Column(name="verified_at",nullable=false,updatable=false) private LocalDateTime verifiedAt;
    @Column(name="content_updated_at",nullable=false,updatable=false) private LocalDateTime contentUpdatedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) @Setter private PublicationStatus status;
    @Column(name="archived_by") @Setter private UUID archivedBy;
    @Column(name="archived_at") @Setter private LocalDateTime archivedAt;
    @Enumerated(EnumType.STRING) @Column(name="post_type",nullable=false,updatable=false,length=30) @Builder.Default private PostType postType=PostType.SINGLE_QUESTION;
    @Column(length=200) @Setter private String title;
    @Column(length=2000) @Setter private String description;
    @Column(name="post_version",nullable=false,updatable=false) @Builder.Default private long postVersion=1;
    @Column(name="item_count",nullable=false,updatable=false) @Builder.Default private int itemCount=1;
    @Column(name="question_set_id",updatable=false) private UUID questionSetId;
    @Column(name="question_set_version",updatable=false) private Long questionSetVersion;
    @Column(name="category_id",updatable=false) private UUID categoryId;
    @Column(name="subject_id",updatable=false) private UUID subjectId;
    public enum PublicationStatus { PUBLISHED, ARCHIVED }
    public enum PostType { SINGLE_QUESTION, QUESTION_SET }
}
