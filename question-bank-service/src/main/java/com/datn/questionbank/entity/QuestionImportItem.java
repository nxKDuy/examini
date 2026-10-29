package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Staged, editable extraction result; JSON columns hold options, flags and validation errors. */
@Entity @Table(name="question_import_items")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class QuestionImportItem {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="job_id",nullable=false,updatable=false) private UUID jobId;
    @Column(nullable=false,updatable=false) private int position;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false,columnDefinition="TEXT") private String content;
    @Enumerated(EnumType.STRING) @Column(name="question_type",nullable=false,length=30) private Question.QuestionType questionType;
    @Enumerated(EnumType.STRING) @Column(name="difficulty_level",nullable=false,length=10) private Question.DifficultyLevel difficultyLevel;
    @Column(name="category_id") private UUID categoryId;
    @Column(nullable=false,columnDefinition="TEXT") private String options;
    @Column(name="expected_answer",columnDefinition="TEXT") private String expectedAnswer;
    @Column(columnDefinition="TEXT") private String explanation;
    @Column(name="source_page",updatable=false) private Integer sourcePage;
    @Enumerated(EnumType.STRING) @Column(name="source_method",nullable=false,updatable=false,length=10) private SourceMethod sourceMethod;
    @Column(nullable=false,updatable=false,precision=3,scale=2) private BigDecimal confidence;
    @Column(name="raw_text",nullable=false,updatable=false,columnDefinition="TEXT") private String rawText;
    @Column(nullable=false,updatable=false,columnDefinition="TEXT") private String flags;
    @Column(name="review_required",nullable=false,updatable=false) private boolean reviewRequired;
    @Column(nullable=false) private boolean reviewed;
    @Column(nullable=false) private boolean excluded;
    @Column(nullable=false) private boolean valid;
    @Column(nullable=false,columnDefinition="TEXT") private String errors;
    @Column(name="imported_question_id") private UUID importedQuestionId;
    @Column(name="imported_at") private LocalDateTime importedAt;
    public enum SourceMethod { TEXT, OCR, AI }
    public boolean importable() { return valid && !excluded && importedQuestionId==null && (!reviewRequired || reviewed); }
}
