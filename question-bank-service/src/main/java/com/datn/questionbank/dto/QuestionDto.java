package com.datn.questionbank.dto;

import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.QuestionReview;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class QuestionDto {
    public record OptionRequest(@NotBlank String content, boolean correct, short displayOrder) {}

    public record CreateRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 5000) String content,
            @NotNull Question.QuestionType questionType,
            @NotNull Question.DifficultyLevel difficultyLevel,
            @Size(max = 500) String source,
            @Size(max = 26) List<@NotNull @Valid OptionRequest> options,
            @Size(max = 10000) String expectedAnswer,
            Question.QuestionScope scope,
            Question.SourceType sourceType,
            @Size(max = 255) String sourceRefId,
            @Size(max = 500) String sourceLabel,
            @Size(max = 2000) String sourceUrl,
            @Size(max = 10000) String sourceExcerpt
    ) {}

    public record UpdateRequest(
            UUID categoryId,
            @Size(min = 1, max = 5000) String content,
            Question.QuestionType questionType,
            Question.DifficultyLevel difficultyLevel,
            @Size(max = 500) String source,
            @Size(max = 26) List<@NotNull @Valid OptionRequest> options,
            @Size(max = 10000) String expectedAnswer,
            Question.SourceType sourceType,
            @Size(max = 255) String sourceRefId,
            @Size(max = 500) String sourceLabel,
            @Size(max = 2000) String sourceUrl,
            @Size(max = 10000) String sourceExcerpt,
            @NotNull @Positive Long questionVersion
    ) {}

    public record OptionResponse(UUID id, String content,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean correct, short displayOrder) {}

    public interface QuestionProjection {
        UUID id();
        String content();
        long questionVersion();
        Question.QuestionStatus status();
        Question.CreationMethod creationMethod();
        Question.SourceType sourceType();
        String sourceRefId();
    }
    public record SafeOptionResponse(UUID id, String content, short displayOrder) {}
    public record SafeQuestionResponse(
            UUID id, UUID categoryId, String categoryName, String content,
            Question.QuestionType questionType, Question.DifficultyLevel difficultyLevel,
            String source, LocalDateTime createdAt, List<SafeOptionResponse> options,
            long questionVersion, Question.QuestionStatus status, Question.QuestionScope scope,
            UUID createdBy, LocalDateTime updatedAt,
            Question.CreationMethod creationMethod, Question.SourceType sourceType,
            String sourceRefId, String sourceLabel, String sourceUrl
    ) implements QuestionProjection {}

    @Builder
    public record QuestionResponse(
            UUID id, UUID categoryId, String categoryName, String content,
            Question.QuestionType questionType, Question.DifficultyLevel difficultyLevel,
            String source, LocalDateTime createdAt, List<OptionResponse> options,
            long questionVersion, Question.QuestionStatus status, Question.QuestionScope scope,
            UUID createdBy, LocalDateTime updatedAt,
            Question.CreationMethod creationMethod, Question.SourceType sourceType,
            String sourceRefId, String sourceLabel, String sourceUrl, String sourceExcerpt,
            @JsonInclude(JsonInclude.Include.NON_NULL) String expectedAnswer
    ) implements QuestionProjection {
        public QuestionResponse withContext(Question.QuestionStatus currentStatus, LocalDateTime currentUpdatedAt) {
            return new QuestionResponse(id, categoryId, categoryName, content, questionType, difficultyLevel,
                    source, createdAt, options, questionVersion, currentStatus, scope, createdBy, currentUpdatedAt,
                    creationMethod, sourceType, sourceRefId, sourceLabel, sourceUrl, sourceExcerpt, expectedAnswer);
        }
        public SafeQuestionResponse withoutAnswers() {
            return new SafeQuestionResponse(id, categoryId, categoryName, content, questionType, difficultyLevel,
                    source, createdAt, options.stream().map(o -> new SafeOptionResponse(o.id(), o.content(), o.displayOrder())).toList(),
                    questionVersion, status, scope, createdBy, updatedAt, creationMethod, sourceType,
                    sourceRefId, sourceLabel, sourceUrl);
        }
    }

    public record VersionResponse(long versionNumber, LocalDateTime createdAt, UUID changedBy, QuestionProjection question) {}
    public record ReviewRequest(@NotNull QuestionReview.Decision decision, @Size(max = 5000) String reviewNote,
                                @NotNull @Positive Long questionVersion) {}
    public record RevisionRequest(@NotNull @Positive Long questionVersion) {}
    public record ReviewResponse(UUID id, long questionVersion, UUID reviewerId, QuestionReview.Decision decision,
                                 String reviewNote, LocalDateTime reviewedAt) {}
    public record ImportResult(int totalRows, int imported, int failed, List<String> errors) {}
}
