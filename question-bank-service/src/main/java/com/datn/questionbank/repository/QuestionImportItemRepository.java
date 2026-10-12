package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionImportItem;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface QuestionImportItemRepository extends JpaRepository<QuestionImportItem,UUID>,JpaSpecificationExecutor<QuestionImportItem> {
    List<QuestionImportItem> findByJobIdOrderByPosition(UUID jobId);
    Optional<QuestionImportItem> findByIdAndJobId(UUID id,UUID jobId);
}
