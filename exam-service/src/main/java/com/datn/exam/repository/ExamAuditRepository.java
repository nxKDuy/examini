package com.datn.exam.repository;
import com.datn.exam.entity.ExamAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ExamAuditRepository extends JpaRepository<ExamAudit, UUID> {
    List<ExamAudit> findByExamIdOrderByTimestampAsc(UUID examId);
}
