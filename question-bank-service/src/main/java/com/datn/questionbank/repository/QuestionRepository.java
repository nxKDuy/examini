package com.datn.questionbank.repository;

import com.datn.questionbank.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID>,
        JpaSpecificationExecutor<Question> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id")
    Optional<Question> findForUpdate(UUID id);
    java.util.List<com.datn.questionbank.entity.Question> findTop300ByCreatedByAndPresentationIsNotNullOrderByUpdatedAtDesc(java.util.UUID createdBy);
}
