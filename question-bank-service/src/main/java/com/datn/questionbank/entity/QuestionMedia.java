package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** An image or audio file attached to questions; the bytes live on the media volume. */
@Entity @Table(name = "question_media")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionMedia {
    public enum Kind { IMAGE, AUDIO }
    @Id private UUID id;
    @Column(name = "owner_id", nullable = false, updatable = false) private UUID ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Kind kind;
    @Column(name = "content_type", nullable = false, length = 60) private String contentType;
    @Column(name = "size_bytes", nullable = false) private int sizeBytes;
    @Column(length = 255) private String name;
    @Column(name = "storage_path", nullable = false, length = 400) private String storagePath;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
