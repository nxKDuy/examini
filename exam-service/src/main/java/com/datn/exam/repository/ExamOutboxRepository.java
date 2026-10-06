package com.datn.exam.repository;

import com.datn.exam.entity.ExamOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ExamOutboxRepository extends JpaRepository<ExamOutboxEvent,UUID> {
    List<ExamOutboxEvent> findTop100ByDeliveredFalseOrderByOccurredAtAsc( );
}
