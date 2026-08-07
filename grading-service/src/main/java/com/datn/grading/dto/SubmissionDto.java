package com.datn.grading.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class SubmissionDto {

    public record SummaryResponse(
            UUID id,
            UUID examId,
            String examTitle,
            UUID studentId,
            String studentName,
            String studentCode,
            String status,
            Float score,
            Float totalScore,
            LocalDateTime submittedAt,
            Integer durationSeconds,
            String feedback
    ) {}

    public record DetailResponse(
            UUID id,
            UUID examId,
            String examTitle,
            UUID studentId,
            String studentName,
            String studentCode,
            String status,
            Float score,
            Float totalScore,
            LocalDateTime submittedAt,
            Integer durationSeconds,
            String feedback,
            List<AnswerDetailDto> answers
    ) {}

    public record AnswerDetailDto(
            UUID questionId,
            String questionContent,
            String questionType,
            Float pointValue,
            String selectedOptionId,
            String selectedOptionContent,
            String textAnswer,
            Boolean isCorrect,
            Float earnedPoints
    ) {}

    public record ManualGradeRequest(
            Float score,
            String feedback
    ) {}
}
