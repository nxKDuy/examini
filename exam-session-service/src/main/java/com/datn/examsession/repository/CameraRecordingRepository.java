package com.datn.examsession.repository;

import com.datn.examsession.entity.CameraRecording;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface CameraRecordingRepository extends JpaRepository<CameraRecording, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from CameraRecording r where r.sessionId=:id")
    Optional<CameraRecording> lockById(@Param("id") UUID id);
    List<CameraRecording> findByExamId(UUID examId);
}
