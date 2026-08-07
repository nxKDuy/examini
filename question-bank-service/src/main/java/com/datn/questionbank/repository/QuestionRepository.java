package com.datn.questionbank.repository;

import com.datn.questionbank.entity.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID>,
        JpaSpecificationExecutor<Question> {

    Page<Question> findByCategoryId(UUID categoryId, Pageable pageable);

    Page<Question> findByDifficultyLevel(Question.DifficultyLevel level, Pageable pageable);

    /** Dùng cho exam-service: lấy ngẫu nhiên theo tiêu chí — exam-service sẽ gọi qua Feign */
    @Query("""
        SELECT q FROM Question q
        WHERE q.category.id = :categoryId
          AND q.difficultyLevel = :difficulty
        ORDER BY FUNCTION('RANDOM')
        """)
    List<Question> findRandomByCategory(UUID categoryId,
                                        Question.DifficultyLevel difficulty,
                                        Pageable pageable);

    long countByCategoryIdAndDifficultyLevel(UUID categoryId, Question.DifficultyLevel difficulty);
}
