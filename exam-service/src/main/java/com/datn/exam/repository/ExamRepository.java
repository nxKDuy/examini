package com.datn.exam.repository;

import com.datn.exam.entity.Exam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExamRepository extends JpaRepository<Exam, UUID> {
    Optional<Exam> findByCode(String code);
    Page<Exam> findByCreatedBy(UUID createdBy, Pageable pageable);
    boolean existsByCode(String code);
}
