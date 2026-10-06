package com.datn.exam.repository;

import com.datn.exam.entity.StartAdmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StartAdmissionRepository extends JpaRepository<StartAdmission,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StartAdmission a where a.id=:id")
    Optional<StartAdmission> lockById(UUID id);
    boolean existsByExamIdAndStateIn(UUID examId,List<StartAdmission.State> states);
    List<StartAdmission> findTop100ByStateInAndExpiresAtLessThanEqualOrderByExpiresAtAsc(List<StartAdmission.State> states,Instant now);
}
