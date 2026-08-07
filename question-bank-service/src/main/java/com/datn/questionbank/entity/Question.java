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

    @Column(length = 500)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<QuestionOption> options = new ArrayList<>();

    public enum QuestionType {
        MULTIPLE_CHOICE, TRUE_FALSE, SHORT_ANSWER, ESSAY
    }

    public enum DifficultyLevel {
        EASY, MEDIUM, HARD
    }
}
