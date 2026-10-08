package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;
public interface QuestionSetRepository extends JpaRepository<QuestionSet,UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from QuestionSet s where s.id=:id")
    java.util.Optional<QuestionSet> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    Page<QuestionSet> findByBankIdAndOwnerUserId(UUID bankId,UUID ownerUserId,Pageable page);
}
