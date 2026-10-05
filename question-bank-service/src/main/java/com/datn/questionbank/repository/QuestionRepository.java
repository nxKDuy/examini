package com.datn.questionbank.repository;

import com.datn.questionbank.entity.Question;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID>,
        JpaSpecificationExecutor<Question> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Question q where q.id = :id")
    Optional<Question> findForUpdate(UUID id);

    @Query(value = """
        SELECT * FROM questions q
        WHERE q.category_id = :category AND q.difficulty_level = :difficulty
          AND q.status = 'APPROVED' AND q.scope = 'PERSONAL'
          AND (:privileged OR q.created_by = :owner)
          AND (CAST(:type AS varchar) IS NULL OR q.question_type = CAST(:type AS varchar))
        ORDER BY RANDOM()
        """, nativeQuery = true)
    List<Question> findEligibleRandom(UUID category, String difficulty, String type,
                                      UUID owner, boolean privileged, Pageable pageable);

}
