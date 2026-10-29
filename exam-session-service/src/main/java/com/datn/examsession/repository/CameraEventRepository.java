package com.datn.examsession.repository;

import com.datn.examsession.entity.CameraEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface CameraEventRepository extends JpaRepository<CameraEvent, UUID> {
    Optional<CameraEvent> findBySessionIdAndClientEventId(UUID sessionId, UUID clientEventId);
    Optional<CameraEvent> findByIdAndSessionId(UUID id, UUID sessionId);
    List<CameraEvent> findBySessionIdOrderByReceivedAtAscIdAsc(UUID sessionId);
    Optional<CameraEvent> findFirstBySessionIdAndTypeOrderByReceivedAtDesc(UUID sessionId, CameraEvent.Type type);
    long countBySessionId(UUID sessionId);
    long countBySessionIdAndReceivedAtAfter(UUID sessionId, Instant after);
    interface SessionCount { UUID getSessionId(); UUID getUserId(); CameraEvent.Type getType(); long getTotal(); Instant getLast(); }
    @Query("select e.sessionId as sessionId, e.userId as userId, e.type as type, count(e) as total, max(e.receivedAt) as last "
            + "from CameraEvent e where e.examId=:exam group by e.sessionId, e.userId, e.type")
    List<SessionCount> countsByExam(@Param("exam") UUID examId);
}
