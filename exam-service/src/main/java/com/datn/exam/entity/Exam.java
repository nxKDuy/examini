package com.datn.exam.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.time.Instant;
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

    @Column(name="start_at") private Instant startAt;
    @Column(name="end_at") private Instant endAt;
    @Column(name="timezone",nullable=false,length=64) @Builder.Default private String timezone="UTC";
    @Enumerated(EnumType.STRING) @Column(name="deadline_rule",nullable=false,length=32)
    @Builder.Default private DeadlineRule deadlineRule=DeadlineRule.FULL_DURATION;
    @Column(name="auto_open",nullable=false) @Builder.Default private boolean autoOpen=false;
    @Column(name="auto_close",nullable=false) @Builder.Default private boolean autoClose=false;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="reminder_offsets_minutes",columnDefinition="jsonb",nullable=false)
    @Builder.Default private List<Integer> reminderOffsetsMinutes=new ArrayList<>(List.of(1440,15));
    @Column(name="schedule_revision",nullable=false) @Builder.Default private long scheduleRevision=0;
    @Column(name="lifecycle_revision",nullable=false) @Builder.Default private long lifecycleRevision=0;
    @Column(name="recipient_revision",nullable=false) @Builder.Default private long recipientRevision=0;
    @Column(name="first_student_started_at") private Instant firstStudentStartedAt;

    @Column(name="result_auto_release",nullable=false) @Builder.Default private boolean resultAutoRelease=true;
    @Column(name="result_show_score",nullable=false) @Builder.Default private boolean resultShowScore=true;
    @Column(name="result_show_question_breakdown",nullable=false) @Builder.Default private boolean resultShowQuestionBreakdown=true;
    @Column(name="result_show_feedback",nullable=false) @Builder.Default private boolean resultShowFeedback=true;
    @Column(name="result_show_correct_answers",nullable=false) @Builder.Default private boolean resultShowCorrectAnswers=false;
    @Column(name="result_show_explanation",nullable=false) @Builder.Default private boolean resultShowExplanation=false;

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
    @CollectionTable(name="exam_followers",joinColumns=@JoinColumn(name="exam_id"))
    @MapKeyColumn(name="user_id")
    @Column(name="created_at",nullable=false)
    @Builder.Default private java.util.Map<UUID,Instant> followers=new java.util.HashMap<>();

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

    public enum DeadlineRule { FULL_DURATION, CAPPED_AT_END, REQUIRE_FULL_WINDOW }

    public enum Status {
        DRAFT, PUBLISHED, OPEN, CLOSED, COMPLETED
    }

    public enum ExamType {
        PRACTICE, EXAM
    }
}
