package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;
public interface QuestionSetRepository extends JpaRepository<QuestionSet,UUID> {
    Page<QuestionSet> findByBankIdAndOwnerUserId(UUID bankId,UUID ownerUserId,Pageable page);
}
