package com.datn.questionbank.repository;
import com.datn.questionbank.entity.OrganizationQuestion;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface OrganizationQuestionRepository extends JpaRepository<OrganizationQuestion,UUID>, JpaSpecificationExecutor<OrganizationQuestion> {
    Optional<OrganizationQuestion> findFirstByBankIdAndSourceQuestionIdOrderBySourceQuestionVersionDesc(UUID bankId,UUID sourceQuestionId);
}
