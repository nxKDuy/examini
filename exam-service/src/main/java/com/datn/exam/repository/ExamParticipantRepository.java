package com.datn.exam.repository;

import com.datn.exam.entity.ExamParticipant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface ExamParticipantRepository extends JpaRepository<ExamParticipant,ExamParticipant.Key> {
    Optional<ExamParticipant> findByExamIdAndUserId(UUID examId,UUID userId);
    boolean existsByExamIdAndUserId(UUID examId,UUID userId);
    boolean existsByExamIdAndUserIdAndStatus(UUID examId,UUID userId,ExamParticipant.Status status);
    long countByExamIdAndStatus(UUID examId,ExamParticipant.Status status);
    List<ExamParticipant> findByExamIdAndStatus(UUID examId,ExamParticipant.Status status);
    List<ExamParticipant> findByExamIdAndUserIdIn(UUID examId,Collection<UUID> userIds);
    List<ExamParticipant> findByUserIdAndExamIdIn(UUID userId,Collection<UUID> examIds);
    @Query("select p.status as status,count(p) as total from ExamParticipant p where p.examId=:examId group by p.status")
    List<Object[]> countsByStatus(@Param("examId") UUID examId);
    @Query("select p.examId,count(p) from ExamParticipant p where p.examId in :examIds and p.status=com.datn.exam.entity.ExamParticipant$Status.ACTIVE group by p.examId")
    List<Object[]> activeCounts(@Param("examIds") Collection<UUID> examIds);
    @Query("select p from ExamParticipant p where p.examId=:examId and (:status is null or p.status=:status) and (:source is null or p.source=:source) and (:userId is null or p.userId=:userId)")
    Page<ExamParticipant> roster(@Param("examId") UUID examId,@Param("status") ExamParticipant.Status status,@Param("source") String source,@Param("userId") UUID userId,Pageable pageable);
}
