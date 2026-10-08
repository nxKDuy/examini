package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name="question_sets")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionSet {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="bank_id",nullable=false,updatable=false) private UUID bankId;
    @Column(name="owner_user_id",nullable=false,updatable=false) private UUID ownerUserId;
    @Column(nullable=false,length=200) private String title;
    @Column(length=2000) private String description;
    @Column(name="current_version",nullable=false) private long currentVersion;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private SetStatus status;
    @Column(name="created_at",nullable=false,updatable=false) private LocalDateTime createdAt;
    @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
    public enum SetStatus { ACTIVE, ARCHIVED }
}
