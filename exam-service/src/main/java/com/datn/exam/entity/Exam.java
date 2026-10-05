package com.datn.exam.entity;

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
@Table(name = "exams", indexes = {
        @Index(name = "idx_exams_code", columnList = "code", unique = true)
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** PRACTICE | EXAM */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ExamType type = ExamType.EXAM;

    @Column(name = "passing_score")
    private Float passingScore;

    @Column(name = "shuffle_questions", nullable = false)
    @Builder.Default
    private boolean shuffleQuestions = false;

    @Column(name = "shuffle_options", nullable = false)
    @Builder.Default
    private boolean shuffleOptions = false;

    // ---- Proctoring settings ----
    @Column(name = "require_cam", nullable = false)
    @Builder.Default
    private boolean requireCam = false;

    @Column(name = "require_mic", nullable = false)
    @Builder.Default
    private boolean requireMic = false;

    @Column(name = "block_tab_switch", nullable = false)
    @Builder.Default
    private boolean blockTabSwitch = false;

    @Column(name = "require_fullscreen", nullable = false)
    @Builder.Default
    private boolean requireFullscreen = false;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "exam_questions", joinColumns = @JoinColumn(name = "exam_id"))
    @Column(name = "question_id")
    @Builder.Default
    @OrderColumn(name="position")
    private List<UUID> questionIds = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "exam_invitations", joinColumns = @JoinColumn(name = "exam_id"))
    @MapKeyColumn(name = "user_id") @Enumerated(EnumType.STRING) @Column(name = "role", nullable = false)
    @Builder.Default private java.util.Map<UUID, MemberRole> invitations = new java.util.HashMap<>();

    public UUID getOwnerUserId() { return createdBy; }

    @ElementCollection
    @CollectionTable(name = "exam_members", joinColumns = @JoinColumn(name = "exam_id"))
    @MapKeyColumn(name = "user_id")
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @Builder.Default
    private java.util.Map<UUID, MemberRole> members = new java.util.HashMap<>();

    @ElementCollection
    @CollectionTable(name = "exam_participants", joinColumns = @JoinColumn(name = "exam_id"))
    @MapKeyColumn(name = "user_id")
    @Column(name = "source", nullable = false)
    @Builder.Default
    private java.util.Map<UUID, String> participants = new java.util.HashMap<>();

    @ElementCollection
    @CollectionTable(name = "exam_question_points", joinColumns = @JoinColumn(name = "exam_id"))
    @MapKeyColumn(name = "question_id")
    @Column(name = "points", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private java.util.Map<UUID, java.math.BigDecimal> points = new java.util.HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false)
    @Builder.Default private AccessMode accessMode = AccessMode.ASSIGNED;
    @Column(name = "max_participants", nullable = false)
    @Builder.Default private int maxParticipants = 100;
    @Column(name = "max_concurrent_sessions", nullable = false)
    @Builder.Default private int maxConcurrentSessions = 50;
    @Column(name = "attempt_limit", nullable = false)
    @Builder.Default private int attemptLimit = 1;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "snapshots", columnDefinition = "jsonb", nullable = false)
    @Builder.Default private java.util.List<com.datn.exam.dto.Snapshot> snapshots = new java.util.ArrayList<>();

    public enum MemberRole { MANAGER, PROCTOR }
    public enum AccessMode { ASSIGNED, JOIN_CODE, PUBLIC }

    public enum Status {
        DRAFT, PUBLISHED, OPEN, CLOSED, COMPLETED
    }

    public enum ExamType {
        PRACTICE, EXAM
    }
}
