package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuestionReviewRepository extends JpaRepository<QuestionReview, UUID> {
    List<QuestionReview> findByQuestionIdOrderByReviewedAtDesc(UUID questionId);
}
