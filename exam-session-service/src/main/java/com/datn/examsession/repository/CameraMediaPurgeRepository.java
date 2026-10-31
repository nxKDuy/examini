package com.datn.examsession.repository;

import com.datn.examsession.entity.CameraMediaPurge;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface CameraMediaPurgeRepository extends JpaRepository<CameraMediaPurge, UUID> {
    List<CameraMediaPurge> findByExamId(UUID examId);

    interface Due { UUID getSessionId(); UUID getExamId(); }
    /** Finished attempts past the deadline + retention that still have camera media. */
    @Query(value = "select s.id as sessionId, s.exam_id as examId from exam_sessions s "
            + "where s.status <> 'IN_PROGRESS' and s.expires_at < :cutoff "
            + "and not exists (select 1 from camera_media_purges p where p.session_id = s.id) "
            + "and (exists (select 1 from camera_events e where e.session_id = s.id and e.snapshot_path is not null) "
            + "  or exists (select 1 from camera_recordings r where r.session_id = s.id)) "
            + "order by s.expires_at limit :limit", nativeQuery = true)
    List<Due> due(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Modifying @Query("update CameraEvent e set e.snapshotPath = null, e.snapshotBytes = null where e.sessionId = :session")
    int clearSnapshots(@Param("session") UUID sessionId);
}
