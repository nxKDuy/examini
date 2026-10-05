package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "question_versions", uniqueConstraints = @UniqueConstraint(columnNames = {"question_id", "version_number"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionVersion {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;
    @Column(name = "version_number", nullable = false, updatable = false)
    private long versionNumber;
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String snapshot;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;
}
