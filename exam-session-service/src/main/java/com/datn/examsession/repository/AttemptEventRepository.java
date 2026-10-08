package com.datn.examsession.repository;
import com.datn.examsession.entity.AttemptEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AttemptEventRepository extends JpaRepository<AttemptEvent,UUID> {
    List<AttemptEvent> findTop100ByGradingDeliveredFalseOrPublicDeliveredFalseOrderByOccurredAtAsc();
}
