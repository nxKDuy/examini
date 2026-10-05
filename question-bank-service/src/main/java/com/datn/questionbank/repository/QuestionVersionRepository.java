package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import org.springframework.data.jpa.repository.Query;
public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, UUID> {
    @Query("select v from QuestionVersion v, Question q where v.questionId = q.id and v.versionNumber = q.questionVersion and q.id in :ids")
    List<QuestionVersion> findCurrentSnapshots(List<UUID> ids);
    List<QuestionVersion> findByQuestionIdOrderByVersionNumberDesc(UUID questionId);
    Optional<QuestionVersion> findByQuestionIdAndVersionNumber(UUID questionId, long versionNumber);
}
