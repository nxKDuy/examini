package com.datn.exam.repository;

import com.datn.exam.entity.AttemptGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface AttemptGrantRepository extends JpaRepository<AttemptGrant,UUID> {
    Optional<AttemptGrant> findByExamIdAndUserIdAndRequestId(UUID examId,UUID userId,String requestId);
    List<AttemptGrant> findByExamIdAndUserIdOrderByCreatedAtAsc(UUID examId,UUID userId);
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true,clearAutomatically=true)
    @org.springframework.data.jpa.repository.Query("delete from AttemptGrant g where g.examId=:examId")
    int deleteAllByExam(@org.springframework.data.repository.query.Param("examId") UUID examId);
}
