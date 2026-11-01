package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "session_answers", indexes = {
        @Index(name = "idx_answers_session", columnList = "session_id")
})
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SessionAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ExamSession session;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    // UUID của option nếu là trắc nghiệm, hoặc nội dung text nếu tự luận
    @Column(name = "answer_content", columnDefinition = "TEXT")
    private String answerContent;

    /** Practice only: when the student checked this answer; it is locked from then on. */
    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** Practice only: the verdict shown (JSON of SessionService.PracticeCheck). */
    @Column(name = "check_result", columnDefinition = "TEXT")
    private String checkResult;

    @UpdateTimestamp
    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;
}
