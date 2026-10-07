package com.datn.grading.repository;
import com.datn.grading.entity.GradingOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface GradingOutboxRepository extends JpaRepository<GradingOutboxEvent,UUID>{List<GradingOutboxEvent> findTop100ByDeliveredFalseOrderByOccurredAtAsc();}
