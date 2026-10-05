package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "question_reviews")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionReview {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;
    @Column(name = "question_version", nullable = false, updatable = false)
    private long questionVersion;
    @Column(name = "reviewer_id", nullable = false, updatable = false)
    private UUID reviewerId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private Decision decision;
    @Column(name = "review_note", updatable = false, columnDefinition = "TEXT")
    private String reviewNote;
    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private LocalDateTime reviewedAt;
    public enum Decision { APPROVED, REJECTED }
}
