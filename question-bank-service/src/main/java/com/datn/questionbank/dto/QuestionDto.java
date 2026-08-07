package com.datn.questionbank.dto;

import com.datn.questionbank.entity.Question;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class QuestionDto {

    public record OptionRequest(
            @NotBlank String content,
            boolean correct,
            short displayOrder
    ) {}

    public record CreateRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 5000) String content,
            @NotNull Question.QuestionType questionType,
            @NotNull Question.DifficultyLevel difficultyLevel,
            String source,
            @NotEmpty @Valid List<OptionRequest> options
    ) {}

    public record UpdateRequest(
            UUID categoryId,
            String content,
            Question.QuestionType questionType,
            Question.DifficultyLevel difficultyLevel,
            String source,
            List<OptionRequest> options
    ) {}

    public record OptionResponse(UUID id, String content, boolean correct, short displayOrder) {}

    public record QuestionResponse(
            UUID id,
            UUID categoryId,
            String categoryName,
            String content,
            Question.QuestionType questionType,
            Question.DifficultyLevel difficultyLevel,
            String source,
            LocalDateTime createdAt,
            List<OptionResponse> options
    ) {}

    /** Kết quả import CSV */
    public record ImportResult(int totalRows, int imported, int failed, List<String> errors) {}
}
