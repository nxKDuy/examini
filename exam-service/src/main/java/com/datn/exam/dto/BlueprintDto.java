package com.datn.exam.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Exam Builder blueprint preview (contracts/exam-builder.md). Nothing is persisted. */
public final class BlueprintDto {
    private BlueprintDto() {}
    public record Row(UUID bankId,UUID categoryId,String questionType,String difficulty,
                      @Min(1) @Max(200) int count,@NotNull @Positive BigDecimal pointsPerQuestion,
                      List<UUID> pinnedQuestionIds) {}
    public record PreviewRequest(@NotEmpty @Size(max=50) List<@Valid Row> rows,boolean shuffle,Long seed,
                                 @Size(max=1000) List<UUID> excludeQuestionIds) {}
    public record Question(UUID questionId,Long questionVersion,String questionType,String content,BigDecimal points,
                           int rowIndex,boolean pinned) {}
    public record RowResult(int rowIndex,UUID bankId,UUID categoryId,String questionType,String difficulty,
                            int requested,int picked,long available,int shortfall,BigDecimal pointsPerQuestion,
                            List<UUID> droppedPinnedIds,List<Question> questions) {}
    public record Preview(List<RowResult> rows,List<Question> questions,int requestedQuestions,int totalQuestions,
                          BigDecimal totalPoints,int totalShortfall,boolean complete,boolean shuffle,long seed) {}
}
