package com.datn.analytics.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnalyticsDto {
    private AnalyticsDto() {}

    /**
     * Score statistics cover GRADED results only; NEEDS_REVIEW/PENDING totals are provisional.
     * Percentages are 0-100 relative to each result's snapshot maxScore.
     */
    public record ExamSummary(
            UUID examId,
            long candidates,
            long submissions,
            long graded,
            long needsReview,
            long pending,
            BigDecimal maxPossibleScore,
            BigDecimal averageScore,
            BigDecimal minScore,
            BigDecimal maxScore,
            BigDecimal averagePercent,
            List<ScoreBucket> scoreDistribution
    ) {}

    /** Half-open percent range [fromPercent, toPercent); the last bucket includes 100. */
    public record ScoreBucket(int fromPercent, int toPercent, long count) {}

    /**
     * Per frozen snapshot item (questionId + questionVersion).
     * correctRate = correct / (attempts - pendingReview); unanswered counts as not correct.
     * averageAwardedPoints excludes PENDING_REVIEW items and counts unanswered as 0.
     */
    public record QuestionStats(
            UUID questionId,
            long questionVersion,
            String questionType,
            int position,
            BigDecimal pointValue,
            long attempts,
            long answered,
            long correct,
            long partial,
            long incorrect,
            long unanswered,
            long pendingReview,
            BigDecimal correctRate,
            BigDecimal averageAwardedPoints
    ) {}

    /** One graded attempt. score is null until the result is GRADED. */
    public record CandidateResult(
            UUID sessionId,
            UUID resultId,
            UUID candidateId,
            Integer attemptNumber,
            String status,
            BigDecimal score,
            BigDecimal maxScore,
            Instant submittedAt,
            Instant gradedAt
    ) {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
}
