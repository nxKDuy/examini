package com.datn.grading.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "exam_results", indexes = {
        @Index(name = "idx_results_session", columnList = "session_id", unique = true),
        @Index(name = "idx_results_user", columnList = "user_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "exam_id", nullable = false)
    private UUID examId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "total_score")
    private Float totalScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "graded_at")
    private Instant gradedAt;

    @OneToMany(mappedBy = "examResult", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<QuestionGrade> questionGrades = new ArrayList<>();

    private Float maxScore;
    private String examTitle;
    @Column(columnDefinition="TEXT") private String feedback;
    private Instant submittedAt;
    private Integer durationSeconds;
    private UUID gradedBy;
    @Column(nullable=false) @Builder.Default private long resultRevision=1;
    private Long releasedRevision;
    @Column(columnDefinition="TEXT") private String pendingPublicReason;
    @Column(columnDefinition="TEXT") private String pendingPrivateAuditNote;
    @Column(nullable=false,length=16) @Builder.Default private String gradingMode="AUTO";
    public enum Status {
        PENDING, NEEDS_REVIEW, GRADED
    }
}
