package com.datn.examsession.repository;
import com.datn.examsession.entity.AttemptEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AttemptEventRepository extends JpaRepository<AttemptEvent,UUID> {
    List<AttemptEvent> findTop100ByGradingDeliveredFalseOrPublicDeliveredFalseOrderByOccurredAtAsc();
    @org.springframework.data.jpa.repository.Query("select e from AttemptEvent e where (e.gradingDelivered=false or e.publicDelivered=false) and e.parked=false and (e.nextAttemptAt is null or e.nextAttemptAt<=:now) order by e.occurredAt asc")
    List<AttemptEvent> findDue(java.time.Instant now,org.springframework.data.domain.Pageable page);
}
