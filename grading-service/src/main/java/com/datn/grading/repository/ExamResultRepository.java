package com.datn.grading.repository;

import com.datn.grading.entity.ExamResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {
    Optional<ExamResult> findBySessionId(UUID sessionId);
    java.util.List<ExamResult> findTop100ByUserIdOrderBySubmittedAtDesc(UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExamResult r where r.id=:id") Optional<ExamResult> lockById(@Param("id")UUID id);

    @Query("SELECT r FROM ExamResult r WHERE r.examId=:examId AND (:status IS NULL OR r.status=:status) AND (:released IS NULL OR (:released=true AND r.releasedRevision IS NOT NULL) OR (:released=false AND r.releasedRevision IS NULL))")
    Page<ExamResult> searchResults(@Param("examId") UUID examId,@Param("status") ExamResult.Status status,@Param("released")Boolean released,Pageable pageable);
    long countByExamIdAndInvalidatedAtIsNull(UUID examId);
    long countByExamIdAndStatusAndInvalidatedAtIsNull(UUID examId,ExamResult.Status status);
    long countByExamIdAndReleasedRevisionIsNotNullAndInvalidatedAtIsNull(UUID examId);
    long countByExamIdAndInvalidatedAtIsNotNull(UUID examId);
    long countByExamId(UUID examId);
    long countByExamIdAndStatus(UUID examId,ExamResult.Status status);
    long countByExamIdAndReleasedRevisionIsNotNull(UUID examId);
    @Query("select coalesce(sum(r.pendingManualItemCount),0) from ExamResult r where r.examId=:examId and r.invalidatedAt is null") long sumPendingManualItems(@Param("examId")UUID examId);
}
