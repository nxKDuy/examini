package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionLineage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface QuestionLineageRepository extends JpaRepository<QuestionLineage,UUID> {}
