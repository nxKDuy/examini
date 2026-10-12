package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionLineage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface QuestionLineageRepository extends JpaRepository<QuestionLineage,UUID> {
    @org.springframework.data.jpa.repository.Query("select distinct l.sourceQuestionId from QuestionLineage l where l.sourcePublicQuestionId = :publicationId and l.copiedBy = :actorId")
    java.util.List<UUID> copiedPublicSourceIds(UUID publicationId, UUID actorId);
    boolean existsBySourceOrganizationQuestionIdAndCopiedBy(UUID publicationId, UUID actorId);
}
