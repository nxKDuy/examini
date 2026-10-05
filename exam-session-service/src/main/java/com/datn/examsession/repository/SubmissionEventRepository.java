package com.datn.examsession.repository;
import com.datn.examsession.entity.SubmissionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SubmissionEventRepository extends JpaRepository<SubmissionEvent,UUID> {
    List<SubmissionEvent> findTop100ByDeliveredFalse();
}
