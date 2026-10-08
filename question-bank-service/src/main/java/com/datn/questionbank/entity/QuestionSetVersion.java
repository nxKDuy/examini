package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name="question_set_versions")
@IdClass(QuestionSetVersion.Key.class)
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionSetVersion {
    @Id @Column(name="question_set_id") private UUID questionSetId;
    @Id @Column(name="version_number") private long versionNumber;
    @Column(nullable=false,columnDefinition="TEXT") private String snapshot;
    @Column(name="created_by",nullable=false) private UUID createdBy;
    @Column(name="created_at",nullable=false) private LocalDateTime createdAt;
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Key implements java.io.Serializable { private UUID questionSetId; private long versionNumber; }
}
