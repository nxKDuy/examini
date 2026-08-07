package com.datn.questionbank.repository;

import com.datn.questionbank.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findBySubjectId(UUID subjectId);
    List<Category> findByParentId(UUID parentId);
    boolean existsByNameIgnoreCaseAndSubjectId(String name, UUID subjectId);
}
