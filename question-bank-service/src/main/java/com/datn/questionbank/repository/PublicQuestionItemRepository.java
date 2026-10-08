package com.datn.questionbank.repository;
import com.datn.questionbank.entity.PublicQuestionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PublicQuestionItemRepository extends JpaRepository<PublicQuestionItem,UUID> {
    List<PublicQuestionItem> findByPublicationIdOrderByPosition(UUID publicationId);
    List<PublicQuestionItem> findByIdIn(Collection<UUID> ids);
    Optional<PublicQuestionItem> findFirstByPublicationIdAndSourceQuestionIdAndSourceQuestionVersion(UUID publicationId,UUID sourceQuestionId,long version);
}
