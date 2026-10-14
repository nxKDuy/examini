package com.datn.exam.repository;

import com.datn.exam.entity.ExamOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ExamOutboxRepository extends JpaRepository<ExamOutboxEvent,UUID> {
    List<ExamOutboxEvent> findTop100ByDeliveredFalseOrderByOccurredAtAsc( );
    @org.springframework.data.jpa.repository.Query("select e from ExamOutboxEvent e where e.delivered=false and e.parked=false and (e.nextAttemptAt is null or e.nextAttemptAt<=:now) order by e.occurredAt asc")
    List<ExamOutboxEvent> findDue(java.time.Instant now,org.springframework.data.domain.Pageable page);
}
