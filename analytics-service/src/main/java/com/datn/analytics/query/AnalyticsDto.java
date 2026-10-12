package com.datn.analytics.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnalyticsDto {
    private AnalyticsDto() {}

    /**
     * Invalidated attempts are excluded from every figure here (candidates, submissions, scores,
     * distribution, submissionRate) and counted only in invalidatedCount.
     * registeredCount/eligibleCount come from Exam's latest participant snapshot and are null
     * (not 0) until one has been received. submissionRate = candidates with at least one valid
     * submission / eligibleCount (0..1, 4 decimals; may exceed 1 if a since-revoked participant
     * submitted); null when eligibleCount is null or 0. Attempt-level pass rate is not reported:
     * no passing score is carried by the events.
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
            List<ScoreBucket> scoreDistribution,
            Integer registeredCount,
            Integer eligibleCount,
            long invalidatedCount,
            BigDecimal submissionRate,
            BigDecimal gradingCompletionRate,
            Kpi kpi
    ) {}

    /**
     * Header KPIs. A null value is "source data not available", never 0; the reason is in
     * unavailable[kpiName]. Invalidated attempts are excluded everywhere.
     * participatedStudents: distinct Students who started an attempt (needs a Session attempt-start event).
     * submittedStudents: distinct Students with a valid submission (denominator for nothing; informational).
     * completionRate = submittedStudents / registeredCount (needs registered snapshot); null otherwise.
     * publishedAverageScore: mean of each Student's latest RELEASED score (needs Grading publication snapshot).
     * needsGradingCount: attempt-level NEEDS_REVIEW + PENDING valid results.
     */
    public record Kpi(
            Long participatedStudents,
            long submittedStudents,
            Integer registeredCount,
            BigDecimal completionRate,
            String completionRateDenominator,
            BigDecimal publishedAverageScore,
            long needsGradingCount,
            java.util.Map<String, String> unavailable
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

    /** One attempt result. score is null until GRADED; invalidated results stay listed for trace only. */
    public record CandidateResult(
            UUID sessionId,
            UUID resultId,
            UUID candidateId,
            Integer attemptNumber,
            String status,
            BigDecimal score,
            BigDecimal maxScore,
            Instant submittedAt,
            Instant gradedAt,
            boolean invalidated
    ) {}

    /** One Student, counting valid recorded submissions only; the latest state is a Grading state. */
    public record StudentSummary(
            UUID candidateId,
            long submittedAttempts,
            long gradedAttempts,
            long needsReviewAttempts,
            long pendingAttempts,
            UUID latestSessionId,
            String latestGradingStatus,
            Instant latestSubmittedAt
    ) {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
}
