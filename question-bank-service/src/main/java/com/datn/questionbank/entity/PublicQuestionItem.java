package com.datn.questionbank.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity @Table(name="public_question_items")
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class PublicQuestionItem {
    @Id private UUID id;
    @Column(name="publication_id",nullable=false,updatable=false) private UUID publicationId;
    @Column(nullable=false,updatable=false) private int position;
    @Column(name="source_question_id",nullable=false,updatable=false) private UUID sourceQuestionId;
    @Column(name="source_question_version",nullable=false,updatable=false) private long sourceQuestionVersion;
    @Column(name="source_bank_id",nullable=false,updatable=false) private UUID sourceBankId;
    @Column(name="source_owner_user_id",nullable=false,updatable=false) private UUID sourceOwnerUserId;
    @Column(nullable=false,updatable=false,columnDefinition="TEXT") private String snapshot;
    @Column(name="search_content",nullable=false,updatable=false,columnDefinition="TEXT") private String searchContent;
    @Enumerated(EnumType.STRING) @Column(name="question_type",nullable=false,updatable=false,length=30) private Question.QuestionType questionType;
    @Enumerated(EnumType.STRING) @Column(name="difficulty_level",nullable=false,updatable=false,length=10) private Question.DifficultyLevel difficultyLevel;
    @Column(name="category_id") private UUID categoryId;
    @Column(name="subject_id") private UUID subjectId;
}
