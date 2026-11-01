package com.datn.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "user_avatars")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAvatar {
    @Id @Column(name = "user_id") private UUID userId;
    @Column(nullable = false) private byte[] content;
    @Column(name = "content_type", nullable = false, length = 30) private String contentType;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
