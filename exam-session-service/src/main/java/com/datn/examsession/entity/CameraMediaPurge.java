package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** Marks an attempt whose camera snapshots and video were deleted by the retention job. */
@Entity @Table(name = "camera_media_purges")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CameraMediaPurge {
    @Id @Column(name = "session_id") private UUID sessionId;
    @Column(name = "exam_id", nullable = false, updatable = false) private UUID examId;
    @Column(name = "purged_at", nullable = false) private Instant purgedAt;
    @Column(nullable = false) private int files;
    @Column(nullable = false) private long bytes;
}
