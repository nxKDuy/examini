package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name="question_import_jobs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionImportJob {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="owner_user_id",nullable=false,updatable=false) private UUID ownerUserId;
    @Column(name="file_name",nullable=false,updatable=false) private String fileName;
    @Enumerated(EnumType.STRING) @Column(name="file_kind",nullable=false,updatable=false,length=10) private FileKind fileKind;
    @Column(name="file_size",nullable=false,updatable=false) private long fileSize;
    @Column(name="page_count",nullable=false) private int pageCount;
    @Column(name="ocr_page_count",nullable=false) private int ocrPageCount;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
    @Column(name="failure_reason",length=1000) private String failureReason;
    @Column(nullable=false,columnDefinition="TEXT") private String warnings;
    @Column(name="category_id") private UUID categoryId;
    @Enumerated(EnumType.STRING) @Column(name="default_difficulty",nullable=false,length=10) private Question.DifficultyLevel defaultDifficulty;
    @Column(name="created_at",nullable=false,updatable=false) private LocalDateTime createdAt;
    @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
    /** Provenance of an AI job (JSON of AiGeneration); null for document jobs. */
    @Column(columnDefinition="TEXT") private String generation;
    public enum FileKind { DOCX, PDF, IMAGE, AI, XLSX, CSV }
    public enum Status { PROCESSING, READY, FAILED, DISCARDED }
}
