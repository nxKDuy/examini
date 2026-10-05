package com.datn.exam.repository;

import com.datn.exam.entity.Exam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ExamRepository extends JpaRepository<Exam, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Exam e where e.id = :id")
    Optional<Exam> lockById(UUID id);
    @org.springframework.data.jpa.repository.Query("select distinct e from Exam e left join e.members m where e.createdBy = :userId or key(m) = :userId")
    Page<Exam> findVisible(UUID userId, Pageable pageable);
    @org.springframework.data.jpa.repository.Query("select distinct e from Exam e join e.participants p where key(p) = :userId")
    List<Exam> findAssigned(UUID userId);
    List<Exam> findByAccessModeAndStatus(Exam.AccessMode accessMode, Exam.Status status);
    Optional<Exam> findByCode(String code);
    Page<Exam> findByCreatedBy(UUID createdBy, Pageable pageable);
    boolean existsByCode(String code);
}
