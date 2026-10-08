package com.datn.examsession.repository;

import com.datn.examsession.entity.ExamSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<ExamSession, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExamSession s where s.id = :id") Optional<ExamSession> lockById(UUID id);
    long countByExamIdAndUserId(UUID examId,UUID userId);
    long countByExamIdAndStatus(UUID examId,ExamSession.Status status);
    long countByExamIdAndStatusAndExpiresAtAfter(UUID examId,ExamSession.Status status,java.time.Instant now);
    boolean existsByExamId(UUID examId);
    Optional<ExamSession> findByExamIdAndStartAdmissionId(UUID examId,UUID startAdmissionId);
    Optional<ExamSession> findTopByExamIdAndUserIdOrderByAttemptNumberDesc(UUID examId,UUID userId);
    java.util.List<ExamSession> findByExamIdAndUserIdIn(UUID examId,java.util.Collection<UUID> userIds);
    java.util.List<ExamSession> findByExamIdAndStatus(UUID examId,ExamSession.Status status);
    java.util.List<ExamSession> findByStatusAndExpiresAtLessThanEqual(ExamSession.Status status,java.time.Instant expiresAt);
    java.util.List<ExamSession> findByExamId(UUID examId);
    @Query("select new com.datn.examsession.repository.SessionRepository$AttemptRow(s.id,s.userId,s.attemptNumber,s.status,s.startTime,s.endTime,s.expiresAt,s.invalidatedAt,s.invalidationReason) from ExamSession s where s.examId=:examId and s.userId in :userIds order by s.userId,s.attemptNumber")
    java.util.List<AttemptRow> attemptRows(UUID examId,java.util.Collection<UUID> userIds);
    /** Light projection: never loads the frozen question jsonb of every attempt. */
    record AttemptRow(UUID sessionId,UUID userId,int attemptNumber,ExamSession.Status status,java.time.LocalDateTime startTime,java.time.LocalDateTime endTime,java.time.Instant expiresAt,java.time.Instant invalidatedAt,String invalidationReason){}
    
    // Tìm session đang diễn ra (để chặn thi lại nếu đang thi)
    @Query("SELECT s FROM ExamSession s WHERE s.examId = :examId AND s.userId = :userId AND s.status = 'IN_PROGRESS'")
    Optional<ExamSession> findActiveSession(UUID examId, UUID userId);
}
