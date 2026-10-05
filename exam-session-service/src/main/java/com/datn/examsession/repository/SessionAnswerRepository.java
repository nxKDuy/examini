package com.datn.examsession.repository;

import com.datn.examsession.entity.SessionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SessionAnswerRepository extends JpaRepository<SessionAnswer, UUID> {
    java.util.List<SessionAnswer> findBySessionId(UUID sessionId);
    Optional<SessionAnswer> findBySessionIdAndQuestionId(UUID sessionId, UUID questionId);
}
