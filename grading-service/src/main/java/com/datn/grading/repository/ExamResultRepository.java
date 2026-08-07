package com.datn.grading.repository;

import com.datn.grading.entity.ExamResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ExamResultRepository extends JpaRepository<ExamResult, UUID> {
    Optional<ExamResult> findBySessionId(UUID sessionId);

    @Query("SELECT r FROM ExamResult r WHERE (:examId IS NULL OR r.examId = :examId) AND (:status IS NULL OR r.status = :status)")
    Page<ExamResult> searchResults(@Param("examId") UUID examId, @Param("status") ExamResult.Status status, Pageable pageable);
}
