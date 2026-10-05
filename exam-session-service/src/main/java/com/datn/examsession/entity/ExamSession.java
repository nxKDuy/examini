package com.datn.examsession.entity;

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
@Table(name = "exam_sessions", indexes = {
        @Index(name = "idx_sessions_user", columnList = "user_id"),
        @Index(name = "idx_sessions_exam", columnList = "exam_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExamSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SessionAnswer> answers = new ArrayList<>();

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProctoringLog> proctoringLogs = new ArrayList<>();

    @Column(name="expires_at",nullable=false) private java.time.Instant expiresAt;
    @Column(name="attempt_number",nullable=false) private int attemptNumber;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="questions",columnDefinition="jsonb",nullable=false)
    @Builder.Default private java.util.List<com.datn.examsession.client.ExamClient.Question> questions = new java.util.ArrayList<>();
    @Column(name="exam_title",nullable=false) private String examTitle;
    public enum Status {
        IN_PROGRESS, SUBMITTED, AUTO_SUBMITTED, CANCELLED
    }
}
