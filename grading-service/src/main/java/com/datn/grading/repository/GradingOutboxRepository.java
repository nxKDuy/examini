package com.datn.grading.repository;
import com.datn.grading.entity.GradingOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.*;
public interface GradingOutboxRepository extends JpaRepository<GradingOutboxEvent,UUID>{
    List<GradingOutboxEvent> findTop100ByDeliveredFalseOrderByOccurredAtAsc();
    /** Pending rows whose backoff has elapsed; parked rows are left for an operator. */
    @Query("select e from GradingOutboxEvent e where e.delivered=false and e.parked=false and (e.nextAttemptAt is null or e.nextAttemptAt<=:now) order by e.occurredAt asc")
    List<GradingOutboxEvent> findDue(Instant now,Pageable page);
}
