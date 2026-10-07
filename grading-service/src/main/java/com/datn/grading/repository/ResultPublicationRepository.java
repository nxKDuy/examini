package com.datn.grading.repository;
import com.datn.grading.entity.ResultPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ResultPublicationRepository extends JpaRepository<ResultPublication,UUID>{
    List<ResultPublication> findByResultIdOrderByResultRevisionAsc(UUID resultId);
    Optional<ResultPublication> findTopByResultIdOrderByResultRevisionDesc(UUID resultId);
}
