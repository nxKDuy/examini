package com.datn.analytics.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PROPOSED contract {@code grading.result.recorded} v1 (see contracts/events.md).
 * State-carried: each event holds the complete current state of one Grading result,
 * so replaying, duplicating or reordering events converges on the newest revision.
 * Answer content and grading keys are deliberately absent.
 */
public record GradingResultEvent(
        UUID eventId,
        int schemaVersion,
        Instant occurredAt,
        UUID resultId,
        long resultRevision,
        UUID sessionId,
        UUID examId,
        UUID candidateId,
        Integer attemptNumber,
        String status,
        BigDecimal totalScore,
        BigDecimal maxScore,
        Instant submittedAt,
        Instant gradedAt,
        List<QuestionItem> questions
) {
    public static final int SCHEMA_VERSION = 1;

    /** Grading outcome for one frozen Exam Question snapshot item. */
    public record QuestionItem(
            UUID questionId,
            long questionVersion,
            String questionType,
            int position,
            BigDecimal pointValue,
            boolean answered,
            boolean needsReview,
            BigDecimal awardedPoints
    ) {}
}
