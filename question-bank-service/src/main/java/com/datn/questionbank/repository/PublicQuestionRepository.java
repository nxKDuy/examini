package com.datn.questionbank.repository;
import com.datn.questionbank.entity.PublicQuestion;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;
public interface PublicQuestionRepository extends JpaRepository<PublicQuestion,UUID>, JpaSpecificationExecutor<PublicQuestion> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PublicQuestion p where p.id=:id")
    java.util.Optional<PublicQuestion> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    java.util.Optional<PublicQuestion> findByContributionId(UUID contributionId);
    java.util.Optional<PublicQuestion> findByQuestionSetIdAndQuestionSetVersion(UUID questionSetId,Long questionSetVersion);
}
