package com.datn.grading.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "question_grades")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "result_id", nullable = false)
    private ExamResult examResult;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    private Float score;
    private Float autoScore;
    private Float manualScore;

    @Column(columnDefinition="TEXT") private String questionContent;
    private String questionType;
    private long questionVersion;
    private int position;
    private Float pointValue;
    @Column(columnDefinition="TEXT") private String answerContent;
    @Column(columnDefinition="TEXT") private String selectedOptionContent;
    private boolean needsReview;
    private UUID manualGradedBy;
    private Instant manualGradedAt;
    @Column(columnDefinition = "TEXT")
    private String feedback; // Feedback từ giáo viên hoặc AI (Gemini)
}
