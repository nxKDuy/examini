package com.datn.grading.repository;
import com.datn.grading.entity.GradingAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface GradingAuditRepository extends JpaRepository<GradingAudit,UUID>{List<GradingAudit> findByResultIdOrderByCreatedAtAsc(UUID resultId);}
