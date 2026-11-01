package com.datn.questionbank.dto;

import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.QuestionReview;
import com.datn.questionbank.entity.QuestionBank;
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
            @Size(max = 10000) String sourceExcerpt,
            @Size(max = 10000) String explanation,
            Presentation presentation
    ) {
        public CreateRequest(UUID categoryId,String content,Question.QuestionType questionType,Question.DifficultyLevel difficultyLevel,
                String source,List<OptionRequest> options,String expectedAnswer,Question.QuestionScope scope,Question.SourceType sourceType,
                String sourceRefId,String sourceLabel,String sourceUrl,String sourceExcerpt) {
            this(categoryId,content,questionType,difficultyLevel,source,options,expectedAnswer,scope,sourceType,sourceRefId,sourceLabel,sourceUrl,sourceExcerpt,null,null);
        }
        public CreateRequest(UUID categoryId,String content,Question.QuestionType questionType,Question.DifficultyLevel difficultyLevel,
                String source,List<OptionRequest> options,String expectedAnswer,Question.QuestionScope scope,Question.SourceType sourceType,
                String sourceRefId,String sourceLabel,String sourceUrl,String sourceExcerpt,String explanation) {
            this(categoryId,content,questionType,difficultyLevel,source,options,expectedAnswer,scope,sourceType,sourceRefId,sourceLabel,sourceUrl,sourceExcerpt,explanation,null);
        }
    }

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
            @NotNull @Positive Long questionVersion,
            @Size(max = 10000) String explanation,
            /** Null keeps the current presentation; an empty object removes it. */
            Presentation presentation
    ) {
        public UpdateRequest(UUID categoryId,String content,Question.QuestionType questionType,Question.DifficultyLevel difficultyLevel,
                String source,List<OptionRequest> options,String expectedAnswer,Question.SourceType sourceType,String sourceRefId,
                String sourceLabel,String sourceUrl,String sourceExcerpt,Long questionVersion) {
            this(categoryId,content,questionType,difficultyLevel,source,options,expectedAnswer,sourceType,sourceRefId,sourceLabel,sourceUrl,sourceExcerpt,questionVersion,null,null);
        }
        public UpdateRequest(UUID categoryId,String content,Question.QuestionType questionType,Question.DifficultyLevel difficultyLevel,
                String source,List<OptionRequest> options,String expectedAnswer,Question.SourceType sourceType,String sourceRefId,
                String sourceLabel,String sourceUrl,String sourceExcerpt,Long questionVersion,String explanation) {
            this(categoryId,content,questionType,difficultyLevel,source,options,expectedAnswer,sourceType,sourceRefId,sourceLabel,sourceUrl,sourceExcerpt,questionVersion,explanation,null);
        }
    }

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
        UUID bankId();
        QuestionBank.BankType bankType();
        String publicationStatus();
        boolean eligible();
    }
    public record SafeOptionResponse(UUID id, String content, short displayOrder) {}
    public record SafeQuestionResponse(
            UUID id, UUID categoryId, String categoryName, String content,
            Question.QuestionType questionType, Question.DifficultyLevel difficultyLevel,
            String source, LocalDateTime createdAt, List<SafeOptionResponse> options,
            long questionVersion, Question.QuestionStatus status, Question.QuestionScope scope,
            UUID createdBy, LocalDateTime updatedAt,
            Question.CreationMethod creationMethod, Question.SourceType sourceType,
            String sourceRefId, String sourceLabel, String sourceUrl, UUID bankId, QuestionBank.BankType bankType,
            String publicationStatus, boolean eligible,
            @JsonInclude(JsonInclude.Include.NON_NULL) Presentation presentation
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
            @JsonInclude(JsonInclude.Include.NON_NULL) String expectedAnswer,
            @JsonInclude(JsonInclude.Include.NON_NULL) String explanation,
            UUID bankId, QuestionBank.BankType bankType, String publicationStatus, boolean eligible,
            @JsonInclude(JsonInclude.Include.NON_NULL) Presentation presentation
    ) implements QuestionProjection {
        public QuestionResponse withContext(Question.QuestionStatus currentStatus, LocalDateTime currentUpdatedAt) {
            return new QuestionResponse(id, categoryId, categoryName, content, questionType, difficultyLevel,
                    source, createdAt, options, questionVersion, currentStatus, scope, createdBy, currentUpdatedAt,
                    creationMethod, sourceType, sourceRefId, sourceLabel, sourceUrl, sourceExcerpt, expectedAnswer, explanation, bankId, bankType, publicationStatus, eligible, presentation);
        }
        public QuestionResponse forExam(UUID resourceId, UUID resourceBank, QuestionBank.BankType type, String publication) {
            return new QuestionResponse(resourceId, categoryId, categoryName, content, questionType, difficultyLevel,
                    source, createdAt, options, questionVersion, Question.QuestionStatus.ACTIVE, scope, createdBy, updatedAt,
                    creationMethod, sourceType, sourceRefId, sourceLabel, sourceUrl, sourceExcerpt, expectedAnswer, explanation,
                    resourceBank, type, publication, true, presentation);
        }
        public SafeQuestionResponse withoutAnswers() {
            return new SafeQuestionResponse(id, categoryId, categoryName, content, questionType, difficultyLevel,
                    source, createdAt, options.stream().map(o -> new SafeOptionResponse(o.id(), o.content(), o.displayOrder())).toList(),
                    questionVersion, status, scope, createdBy, updatedAt, creationMethod, sourceType,
                    sourceRefId, sourceLabel, sourceUrl, bankId, bankType, publicationStatus, eligible, presentation);
        }
    }

    public record VersionResponse(long versionNumber, LocalDateTime createdAt, UUID changedBy, QuestionProjection question) {}
    public record ReviewRequest(@NotNull QuestionReview.Decision decision, @Size(max = 5000) String reviewNote,
                                @NotNull @Positive Long questionVersion) {}
    public record RevisionRequest(@NotNull @Positive Long questionVersion) {}
    public record ReviewResponse(UUID id, long questionVersion, UUID reviewerId, QuestionReview.Decision decision,
                                 String reviewNote, LocalDateTime reviewedAt) {}
    /** Exam Builder bulk selection filters; all optional, combined with AND. */
    public record EligibleFilter(UUID bankId, UUID categoryId, Question.QuestionType questionType,
            Question.DifficultyLevel difficulty, @Size(max = 200) String text) {}
    public record EligibleIdsResponse(List<UUID> ids, long total, boolean truncated) {}
    /** One blueprint row: random ACTIVE eligible picks; shortfall = requested - ids.size(), never backfilled. */
    public record EligibleRandomRequest(UUID bankId, UUID categoryId, Question.QuestionType questionType,
            Question.DifficultyLevel difficulty, @Min(1) @Max(200) int count,
            @Size(max = 1000) List<@NotNull UUID> excludeQuestionIds) {}
    public record EligibleRandomResponse(List<UUID> ids, int requested, long available) {}
    /** Exam matrix: how many eligible questions exist per topic (category) and difficulty. */
    public record MatrixCountsRequest(UUID bankId, @NotEmpty @Size(max = 50) List<@NotNull UUID> categoryIds, Question.QuestionType questionType) {}
    public record MatrixCount(UUID categoryId, Question.DifficultyLevel difficulty, long available) {}
    public record ImportResult(int totalRows, int imported, int failed, List<String> errors) {}
}
