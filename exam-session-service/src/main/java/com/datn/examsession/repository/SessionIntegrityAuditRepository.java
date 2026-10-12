package com.datn.examsession.repository;
import com.datn.examsession.entity.SessionIntegrityAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.UUID;
public interface SessionIntegrityAuditRepository extends JpaRepository<SessionIntegrityAudit,UUID> {
    Page<SessionIntegrityAudit> findBySessionId(UUID sessionId,Pageable pageable);
}
