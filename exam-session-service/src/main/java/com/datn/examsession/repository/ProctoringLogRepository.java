package com.datn.examsession.repository;

import com.datn.examsession.entity.ProctoringLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProctoringLogRepository extends JpaRepository<ProctoringLog, UUID> {}
