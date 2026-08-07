package com.datn.examsession.repository;

import com.datn.examsession.entity.ExamSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<ExamSession, UUID> {
    Optional<ExamSession> findByExamIdAndUserId(UUID examId, UUID userId);
    
    // Tìm session đang diễn ra (để chặn thi lại nếu đang thi)
    @Query("SELECT s FROM ExamSession s WHERE s.examId = :examId AND s.userId = :userId AND s.status = 'IN_PROGRESS'")
    Optional<ExamSession> findActiveSession(UUID examId, UUID userId);
}
