package com.datn.grading.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A student's own results across exams, as far as each exam's result policy lets the student see them. */
public final class ProgressDto {
    private ProgressDto() {}
    /** percent/score are null when the result is not published yet or the exam hides scores. */
    public record Item(UUID sessionId, UUID examId, String title, int attemptNumber, String phase, Instant submittedAt, Instant releasedAt,
                       Float score, Float maxScore, Float percent, boolean invalidated) {}
    /** earned/possible points of one question type over results whose breakdown is visible. */
    public record TypeStat(String questionType, int questions, float earned, float possible, float percent) {}
    public record Overview(int exams, int attempts, int published, Float averagePercent, Float bestPercent, Float latestPercent,
                           Float trend, List<TypeStat> byType) {}
    public record Progress(Overview overview, List<Item> items) {}
    public record Tip(String kind, String text) {}
    public record Insights(String source, String summary, List<Tip> tips, boolean aiAvailable) {}
}
