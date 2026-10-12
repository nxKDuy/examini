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
    java.util.Optional<PublicQuestion> findFirstBySourceQuestionIdAndSourceOwnerUserIdAndPostTypeAndStatusOrderBySourceQuestionVersionDesc(
            UUID sourceQuestionId,UUID sourceOwnerUserId,PublicQuestion.PostType postType,PublicQuestion.PublicationStatus status);
    java.util.Optional<PublicQuestion> findFirstBySourceQuestionIdAndSourceOwnerUserIdAndPostTypeOrderBySourceQuestionVersionDesc(
            UUID sourceQuestionId,UUID sourceOwnerUserId,PublicQuestion.PostType postType);
    java.util.Optional<PublicQuestion> findFirstByQuestionSetIdOrderByQuestionSetVersionDesc(UUID questionSetId);
    java.util.Optional<PublicQuestion> findFirstByQuestionSetIdAndStatusOrderByQuestionSetVersionDesc(
            UUID questionSetId,PublicQuestion.PublicationStatus status);
}
