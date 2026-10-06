package com.datn.exam.repository;

import com.datn.exam.entity.Exam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

public interface ExamRepository extends JpaRepository<Exam, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from Exam e where e.id = :id")
    Optional<Exam> lockById(UUID id);
    @org.springframework.data.jpa.repository.Query("select distinct e from Exam e left join e.members m where e.createdBy = :userId or key(m) = :userId")
    Page<Exam> findVisible(UUID userId, Pageable pageable);
    @org.springframework.data.jpa.repository.Query("select distinct e from Exam e join e.participants p where key(p) = :userId")
    List<Exam> findAssigned(UUID userId);
    @org.springframework.data.jpa.repository.Query("select e from Exam e join e.followers f where key(f)=:userId")
    List<Exam> findFollowed(@Param("userId") UUID userId);
    @org.springframework.data.jpa.repository.Query("select distinct e from Exam e left join e.participants p left join e.followers f where (key(p)=:userId or key(f)=:userId) and e.status in :statuses and ((e.startAt is not null and e.startAt<:toAt and (e.endAt is null or e.endAt>:fromAt)) or (e.startAt is null and e.endAt is not null and e.endAt>:fromAt and e.endAt<:toAt) or (:includeUnscheduled=true and e.startAt is null and e.endAt is null))")
    Page<Exam> findStudentCalendar(@Param("userId") UUID userId,@Param("fromAt") java.time.Instant fromAt,@Param("toAt") java.time.Instant toAt,@Param("includeUnscheduled") boolean includeUnscheduled,@Param("statuses") List<Exam.Status> statuses,Pageable pageable);
    @org.springframework.data.jpa.repository.Query("select distinct e from Exam e left join e.members m where (e.createdBy=:userId or key(m)=:userId) and ((e.startAt is not null and e.startAt<:toAt and (e.endAt is null or e.endAt>:fromAt)) or (e.startAt is null and e.endAt is not null and e.endAt>:fromAt and e.endAt<:toAt) or (:includeUnscheduled=true and e.startAt is null and e.endAt is null))")
    Page<Exam> findTeacherCalendar(@Param("userId") UUID userId,@Param("fromAt") java.time.Instant fromAt,@Param("toAt") java.time.Instant toAt,@Param("includeUnscheduled") boolean includeUnscheduled,Pageable pageable);
    List<Exam> findByStatusInAndAutoOpenTrue(List<Exam.Status> statuses);
    List<Exam> findByStatusInAndAutoCloseTrue(List<Exam.Status> statuses);
    List<Exam> findByAccessModeAndStatus(Exam.AccessMode accessMode, Exam.Status status);
    List<Exam> findByAccessModeAndStatusIn(Exam.AccessMode accessMode,List<Exam.Status> statuses);
    Optional<Exam> findByCode(String code);
    Page<Exam> findByCreatedBy(UUID createdBy, Pageable pageable);
    boolean existsByCode(String code);
}
