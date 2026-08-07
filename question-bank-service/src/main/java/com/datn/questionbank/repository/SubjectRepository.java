package com.datn.questionbank.repository;

import com.datn.questionbank.entity.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {
    boolean existsByNameIgnoreCase(String name);
}
