package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One camera observation reported by the browser (or the enrollment photo). Immutable evidence. */
@Entity @Table(name = "camera_events")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class CameraEvent {
    @Id private UUID id;
    @Column(name = "session_id", nullable = false, updatable = false) private UUID sessionId;
    @Column(name = "exam_id", nullable = false, updatable = false) private UUID examId;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(name = "client_event_id", nullable = false, updatable = false) private UUID clientEventId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30) private Type type;
    @Column(name = "occurred_at", updatable = false) private Instant occurredAt;
    @Column(name = "received_at", nullable = false, updatable = false) private Instant receivedAt;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false, updatable = false) private Map<String, Object> detail;
    @Column(name = "snapshot_path", updatable = false, length = 400) private String snapshotPath;
    @Column(name = "snapshot_bytes", updatable = false) private Integer snapshotBytes;

    public enum Type {
        /** Enrollment photo taken before the first question; later checks compare against it. */
        REFERENCE,
        NO_FACE, MULTIPLE_FACES, FACE_MISMATCH, LOOKING_AWAY,
        /** Camera stopped (track ended, permission revoked, device unplugged) / came back. */
        CAMERA_OFF, CAMERA_ON
    }
}
