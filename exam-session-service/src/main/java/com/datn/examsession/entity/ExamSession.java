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
    @Column(name="invalidated_at") private java.time.Instant invalidatedAt;
    @Column(name="invalidated_by") private UUID invalidatedBy;
    @Column(name="invalidation_reason",columnDefinition="TEXT") private String invalidationReason;
    public boolean isInvalidated(){return invalidatedAt!=null;}
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="questions",columnDefinition="jsonb",nullable=false)
    @Builder.Default private java.util.List<com.datn.examsession.client.ExamClient.Question> questions = new java.util.ArrayList<>();
    @Column(name="exam_title",nullable=false) private String examTitle;
    /** Practice attempt (exam type PRACTICE): answers may be checked one by one while answering. */
    @Column(name="practice",nullable=false) @Builder.Default private boolean practice=false;
    @Column(name="schedule_revision",nullable=false) @Builder.Default private long scheduleRevision=0;
    @Column(name="start_admission_id") private UUID startAdmissionId;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="integrity_policy",columnDefinition="jsonb",nullable=false,updatable=false)
    @Builder.Default private com.datn.examsession.dto.IntegrityPolicy integrityPolicy = com.datn.examsession.dto.IntegrityPolicy.disabled();
    public enum Status {
        IN_PROGRESS, SUBMITTED, AUTO_SUBMITTED, CANCELLED
    }
}
