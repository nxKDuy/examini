package com.datn.examsession.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** Result of comparing an attempt's enrollment photo with the student's face profile. */
@Entity @Table(name = "camera_identity_checks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CameraIdentityCheck {
    public enum Outcome { ENROLLED, MATCH, MISMATCH }
    @Id @Column(name = "session_id") private UUID sessionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Outcome outcome;
    private Double distance;
    @Column(nullable = false) private double threshold;
    @Column(nullable = false) private int attempts;
    @Column(name = "checked_at", nullable = false) private Instant checkedAt;
    @Column(name = "profile_created_at") private Instant profileCreatedAt;
}
