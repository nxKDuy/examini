package com.datn.questionbank.repository;
import com.datn.questionbank.entity.OrganizationContribution;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface OrganizationContributionRepository extends JpaRepository<OrganizationContribution,UUID>, JpaSpecificationExecutor<OrganizationContribution> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from OrganizationContribution c where c.id=:id")
 Optional<OrganizationContribution> findForUpdate(UUID id);
 boolean existsByBankIdAndSourceQuestionIdAndSourceQuestionVersion(UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion);
}
