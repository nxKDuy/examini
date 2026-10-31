package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** Progress of one attempt's WebM recording; the chunks themselves live on disk (CameraStorage). */
@Entity @Table(name = "camera_recordings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CameraRecording {
    @Id @Column(name = "session_id") private UUID sessionId;
    @Column(name = "exam_id", nullable = false, updatable = false) private UUID examId;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(name = "mime_type", nullable = false, length = 60) private String mimeType;
    @Column(nullable = false) private int chunks;
    @Column(nullable = false) private long bytes;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "last_chunk_at") private Instant lastChunkAt;
}
