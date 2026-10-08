package com.datn.questionbank.repository;
import com.datn.questionbank.entity.PublicQuestion;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;
public interface PublicQuestionRepository extends JpaRepository<PublicQuestion,UUID>, JpaSpecificationExecutor<PublicQuestion> {
    java.util.Optional<PublicQuestion> findByContributionId(UUID contributionId);
    java.util.Optional<PublicQuestion> findByQuestionSetIdAndQuestionSetVersion(UUID questionSetId,Long questionSetVersion);
}
