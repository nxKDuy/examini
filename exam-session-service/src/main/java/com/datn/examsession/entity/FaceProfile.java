package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A student's face profile, taken at their first camera-proctored exam (128-number descriptor + photo). */
@Entity @Table(name = "face_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FaceProfile {
    @Id @Column(name = "user_id") private UUID userId;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false) private List<Double> descriptor;
    @Column(name = "photo_path", nullable = false, length = 400) private String photoPath;
    @Column(name = "source_session_id") private UUID sourceSessionId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
