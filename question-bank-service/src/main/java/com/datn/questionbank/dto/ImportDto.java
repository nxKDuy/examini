package com.datn.questionbank.dto;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.entity.QuestionImportJob.FileKind;
import com.datn.questionbank.entity.QuestionImportJob.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Owner-only staged document import; items carry the author's own keys. See contracts/question-import.md. */
public class ImportDto {
    public enum ItemFilter { ALL, NEEDS_REVIEW, INVALID, IMPORTABLE, IMPORTED, EXCLUDED }
    public record ImportOption(@NotBlank @Size(max = 5000) String content, boolean correct, short displayOrder) {}
    public record SourceRef(String fileName, Integer page, SourceMethod method) {}
    public record ItemError(String code, String message) {}
    public record ImportItemResponse(UUID id, int position, long version, String content, QuestionType questionType,
            DifficultyLevel difficultyLevel, UUID categoryId, List<ImportOption> options, String expectedAnswer,
            String explanation, SourceRef source, BigDecimal confidence, String rawText, List<String> flags,
            boolean reviewRequired, boolean reviewed, boolean excluded, boolean valid, List<ItemError> errors,
            boolean importable, UUID importedQuestionId, LocalDateTime importedAt) {}
    public record ImportSummary(long total, long valid, long invalid, long needsReview, long importable, long imported, long excluded) {}
    public record ImportJobResponse(UUID id, String fileName, FileKind fileKind, long fileSize, int pageCount, int ocrPageCount,
            Status status, String failureReason, List<String> warnings, UUID categoryId, DifficultyLevel defaultDifficulty,
            LocalDateTime createdAt, LocalDateTime updatedAt, ImportSummary summary, AiGeneration generation) {}
    /** null = unchanged; options replace all; "" clears expectedAnswer/explanation. */
    public record ItemUpdate(@NotNull Long version, @Size(min = 1, max = 5000) String content, QuestionType questionType,
            DifficultyLevel difficultyLevel, UUID categoryId, @Size(max = 26) List<@NotNull @Valid ImportOption> options,
            @Size(max = 10000) String expectedAnswer, @Size(max = 10000) String explanation, Boolean reviewed, Boolean excluded) {}
    public record ImportRequest(@Size(max = 300) List<@NotNull UUID> itemIds, UUID bankId) {}
    public record ImportedItem(UUID itemId, UUID questionId) {}
    public record SkippedItem(UUID itemId, String reason) {}
    public record ImportJobResult(List<ImportedItem> imported, List<SkippedItem> skipped, ImportSummary summary) {}

    // ── AI generation (additive; contracts/question-import.md "AI generation") ──
    public static final int AI_MAX_QUESTIONS = 20, AI_MAX_MATERIAL = 20000;
    /** Generate staged items with AI. material is optional knowledge text; examples come from the actor's own bank. */
    public record AiGenerateRequest(@NotBlank @Size(max = 500) String topic, @Size(max = AI_MAX_MATERIAL) String material,
            @NotNull UUID categoryId, @Min(1) @Max(AI_MAX_QUESTIONS) Integer count,
            @Size(min = 1, max = 4) List<@NotNull QuestionType> questionTypes, DifficultyLevel difficulty,
            @Pattern(regexp = "vi|en") String language, Boolean useBankExamples) {}
    /** Provenance shown on an AI job; null on document jobs. */
    public record AiGeneration(String topic, String model, int requestedCount, List<QuestionType> questionTypes,
            DifficultyLevel difficulty, String language, boolean hasMaterial, int materialLength, int exampleCount) {}
    public record AiStatus(boolean available, String model, int maxQuestions, int maxMaterialChars) {}
}
