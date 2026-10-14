package com.datn.examsession.repository;
import com.datn.examsession.entity.SubmissionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SubmissionEventRepository extends JpaRepository<SubmissionEvent,UUID> {
    List<SubmissionEvent> findTop100ByDeliveredFalse();
    @org.springframework.data.jpa.repository.Query("select e from SubmissionEvent e where e.delivered=false and e.parked=false and (e.nextAttemptAt is null or e.nextAttemptAt<=:now)")
    List<SubmissionEvent> findDue(java.time.Instant now,org.springframework.data.domain.Pageable page);
}
