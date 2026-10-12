package com.datn.examsession.repository;
import com.datn.examsession.entity.ExamIntegrityLock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ExamIntegrityLockRepository extends JpaRepository<ExamIntegrityLock,UUID> {}
