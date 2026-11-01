package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "questions", indexes = {
        @Index(name = "idx_questions_category", columnList = "category_id"),
        @Index(name = "idx_questions_difficulty", columnList = "difficulty_level"),
        @Index(name = "idx_questions_type", columnList = "question_type")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 30)
    private QuestionType questionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty_level", nullable = false, length = 10)
    private DifficultyLevel difficultyLevel;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "bank_id", nullable = false, updatable = false)
    private UUID bankId;

    @Column(length = 500)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private QuestionStatus status = QuestionStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private QuestionScope scope = QuestionScope.PERSONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "creation_method", nullable = false, length = 30)
    @Builder.Default
    private CreationMethod creationMethod = CreationMethod.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    @Builder.Default
    private SourceType sourceType = SourceType.NONE;

    @Column(name = "source_ref_id", length = 255)
    private String sourceRefId;
    @Column(name = "source_label", length = 500)
    private String sourceLabel;
    @Column(name = "source_url", length = 2000)
    private String sourceUrl;
    @Column(name = "source_excerpt", columnDefinition = "TEXT")
    private String sourceExcerpt;
    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;
    @Column(columnDefinition = "TEXT")
    private String explanation;

    /** Media, reading passage and per-type limits; null for a plain text question. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private com.datn.questionbank.dto.Presentation presentation;

    @Column(name = "question_version", nullable = false)
    @Builder.Default
    private long questionVersion = 1;

    // Persistence concurrency counter is separate from the public content revision.
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    public enum QuestionStatus { DRAFT, ACTIVE, ARCHIVED, /* historical snapshots only */ PENDING_REVIEW, APPROVED, REJECTED }
    public enum QuestionScope { PERSONAL, ORGANIZATION, SYSTEM }
    public enum CreationMethod { MANUAL, IMPORT, AI_GENERATED, COPIED }
    public enum SourceType { NONE, USER_DOCUMENT, EXTERNAL_WEB, ORGANIZATION_BANK, PUBLIC_BANK, SYSTEM_BANK }

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<QuestionOption> options = new ArrayList<>();

    public enum QuestionType {
        MULTIPLE_CHOICE, TRUE_FALSE, SHORT_ANSWER, ESSAY,
        /** Answered with a voice recording; graded by a teacher. */
        SPEAKING,
        /** Several options may be correct; the student ticks all of them (all-or-nothing). */
        MULTIPLE_RESPONSE,
        /** 2-8 statements each judged true/false (THPT 2025 format); option.correct = the statement is true. */
        TRUE_FALSE_GROUP,
        /** The stem marks gaps with ___; option i holds the accepted answers of gap i ("a | b"). Partial credit per gap. */
        FILL_BLANK,
        /** Each option is a pair "left => right"; the student matches every left item. Partial credit per pair. */
        MATCHING
    }

    public enum DifficultyLevel {
        EASY, MEDIUM, HARD
    }
}
