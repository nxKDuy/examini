package com.datn.examsession.repository;

import com.datn.examsession.entity.IntegrityEvent;
import com.datn.examsession.dto.IntegrityDto.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.*;
import java.time.Instant;
import java.util.*;

public interface IntegrityEventRepository extends JpaRepository<IntegrityEvent,UUID> {
    Optional<IntegrityEvent> findBySessionIdAndClientEventId(UUID sessionId,UUID clientEventId);
    long countBySessionId(UUID sessionId);
    long countBySessionIdAndReceivedAtAfter(UUID sessionId,Instant since);
    long countBySessionIdAndCountedTrue(UUID sessionId);
    Optional<IntegrityEvent> findFirstBySessionIdAndCountedTrueAndTypeInAndEffectiveAtBetweenOrderByEffectiveAtAsc(UUID sessionId,Collection<EventType> types,Instant from,Instant to);
    @Query("select e from IntegrityEvent e where e.examId=:exam and (:session is null or e.sessionId=:session) and (:type is null or e.type=:type)")
    Page<IntegrityEvent> search(UUID exam,UUID session,EventType type,Pageable page);
    interface TypeCount { EventType getType(); long getTotal(); }
    @Query("select e.type as type,count(e) as total from IntegrityEvent e where e.examId=:exam and (:session is null or e.sessionId=:session) group by e.type")
    List<TypeCount> counts(UUID exam,UUID session);
}
