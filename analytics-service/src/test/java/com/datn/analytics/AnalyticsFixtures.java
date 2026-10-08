package com.datn.analytics;

import com.datn.analytics.ingest.GradingResultEvent;
import com.datn.analytics.ingest.GradingResultEvent.QuestionItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnalyticsFixtures {
    private AnalyticsFixtures() {}

    public static BigDecimal n(String value) { return new BigDecimal(value); }

    public static QuestionItem item(UUID question, int position, String points, boolean answered, boolean review, String awarded) {
        return new QuestionItem(question, 1, review ? "ESSAY" : "MULTIPLE_CHOICE", position, n(points), answered, review,
                awarded == null ? null : n(awarded));
    }

    public static GradingResultEvent result(UUID exam, UUID session, UUID result, UUID candidate, long revision, String status,
                                     String total, String max, List<QuestionItem> questions) {
        Instant submitted = Instant.parse("2026-10-01T08:00:00Z").plusSeconds(revision);
        return new GradingResultEvent(UUID.randomUUID(), 1, Instant.now(), result, revision, session, exam, candidate, 1,
                status, total == null ? null : n(total), n(max), submitted,
                "GRADED".equals(status) ? submitted.plusSeconds(60) : null, questions);
    }
}
