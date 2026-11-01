package com.datn.questionbank.repository;

import com.datn.questionbank.entity.QuestionMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface QuestionMediaRepository extends JpaRepository<QuestionMedia, UUID> {}
