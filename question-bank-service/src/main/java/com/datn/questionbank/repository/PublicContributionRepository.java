package com.datn.questionbank.repository;
import com.datn.questionbank.entity.PublicContribution;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface PublicContributionRepository extends JpaRepository<PublicContribution,UUID>, JpaSpecificationExecutor<PublicContribution> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from PublicContribution c where c.id=:id")
    Optional<PublicContribution> findForUpdate(UUID id);
    Optional<PublicContribution> findBySourceQuestionIdAndSourceQuestionVersion(UUID sourceQuestionId,long sourceQuestionVersion);
    boolean existsBySourceQuestionIdAndSourceQuestionVersion(UUID id,long version);
}
