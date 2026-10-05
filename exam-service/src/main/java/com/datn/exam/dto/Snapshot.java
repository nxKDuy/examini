package com.datn.exam.dto;
import java.util.*;
import java.math.BigDecimal;
public record Snapshot(UUID questionId, long questionVersion, String questionType, String content,
                       List<Option> options, BigDecimal points, String expectedAnswer, int position) {
    public record Option(UUID id, String content, int displayOrder, boolean correct) {}
    public Safe safe() { return new Safe(questionId, questionVersion, questionType, content,
        options.stream().map(o -> new SafeOption(o.id(), o.content(), o.displayOrder())).toList(), points, position); }
    public record SafeOption(UUID id, String content, int displayOrder) {}
    public record Safe(UUID questionId, long questionVersion, String questionType, String content,
                       List<SafeOption> options, BigDecimal points, int position) {}
}
