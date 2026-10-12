package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionImportJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;
public interface QuestionImportJobRepository extends JpaRepository<QuestionImportJob,UUID> {
    Page<QuestionImportJob> findByOwnerUserId(UUID owner,Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select j from QuestionImportJob j where j.id=:id")
    Optional<QuestionImportJob> findByIdForUpdate(@Param("id") UUID id);
    @Modifying @Query("update QuestionImportJob j set j.status='FAILED',j.failureReason=:reason,j.updatedAt=:now where j.status='PROCESSING' and j.updatedAt<:before")
    int failStale(@Param("before") LocalDateTime before,@Param("now") LocalDateTime now,@Param("reason") String reason);
}
