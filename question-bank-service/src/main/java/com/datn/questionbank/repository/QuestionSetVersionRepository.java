package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionSetVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface QuestionSetVersionRepository extends JpaRepository<QuestionSetVersion,QuestionSetVersion.Key> {}
