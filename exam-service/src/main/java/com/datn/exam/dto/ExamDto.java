package com.datn.exam.dto;

import com.datn.exam.entity.Exam;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ExamDto {

    /** Tạo đề thủ công — payload từ FE Wizard */
    public record ManualCreateRequest(
            @NotBlank @Size(max = 255) String title,
            String description,
            @Min(1) int durationMinutes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String type,                // "PRACTICE" | "EXAM"
            Float passingScore,
            boolean shuffleQuestions,
            boolean shuffleOptions,
            // Proctoring
            boolean requireCam,
            boolean requireMic,
            boolean blockTabSwitch,
            boolean requireFullscreen,
            @NotNull List<QuestionWeightDto> questions
    ) {}

    /** Cặp câu hỏi + trọng số điểm */
    public record QuestionWeightDto(
            @NotNull UUID questionId,
            @Min(0) double weight
    ) {}

    /** Tự động sinh đề */
    public record AutoGenerateRequest(
            @NotBlank @Size(max = 255) String title,
            String description,
            @Min(1) int durationMinutes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            @NotNull UUID categoryId,
            @Min(0) int easyCount,
            @Min(0) int mediumCount,
            @Min(0) int hardCount
    ) {}

    /** Response trả về cho FE */
    public record Response(
            UUID id,
            String code,
            String title,
            String description,
            int durationMinutes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status,
            String type,
            Float passingScore,
            Float totalScore,
            boolean shuffleQuestions,
            boolean shuffleOptions,
            boolean requireCam,
            boolean requireMic,
            boolean blockTabSwitch,
            boolean requireFullscreen,
            LocalDateTime createdAt,
            List<UUID> questionIds,
            // Thống kê (nullable — chỉ có khi query kèm stats)
            Long studentCount,
            Float avgScore
    ) {
        /** Factory method từ entity (không có stats) */
        public static Response from(Exam e) {
            return new Response(
                    e.getId(), e.getCode(), e.getTitle(), e.getDescription(),
                    e.getDurationMinutes(), e.getStartTime(), e.getEndTime(),
                    e.getStatus().name(), e.getType().name(),
                    e.getPassingScore(), null,           // totalScore tính sau
                    e.isShuffleQuestions(), e.isShuffleOptions(),
                    e.isRequireCam(), e.isRequireMic(),
                    e.isBlockTabSwitch(), e.isRequireFullscreen(),
                    e.getCreatedAt(), e.getQuestionIds(),
                    null, null
            );
        }
    }
}
