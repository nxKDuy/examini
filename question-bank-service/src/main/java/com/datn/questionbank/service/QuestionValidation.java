package com.datn.questionbank.service;

import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.QuestionOption;
import java.net.URI;
import java.util.HashSet;

/** One validator for manual create, edit, activation, publication and CSV. */
public final class QuestionValidation {
    private QuestionValidation() {}
    public static void validate(Question q) {
        text(q.getContent(), 5000, "Nội dung câu hỏi");
        if (q.getQuestionType() == null || q.getDifficultyLevel() == null || q.getCategory() == null)
            throw new IllegalArgumentException("Thiếu loại câu hỏi, độ khó hoặc danh mục");
        limit(q.getSource(), 500, "Nguồn");
        limit(q.getSourceLabel(), 500, "Nhãn nguồn");
        limit(q.getSourceRefId(), 255, "Mã tham chiếu nguồn");
        limit(q.getSourceExcerpt(), 10000, "Trích đoạn nguồn");
        limit(q.getExpectedAnswer(), 10000, "Đáp án mong đợi");
        limit(q.getSourceUrl(), 2000, "URL nguồn");
        if (q.getSourceUrl() != null && !q.getSourceUrl().isBlank()) {
            try {
                URI uri = URI.create(q.getSourceUrl());
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("URL nguồn phải là HTTP(S) hợp lệ"); }
        }
        var options = q.getOptions();
        var type = q.getQuestionType();
        if (isChoice(type)) {
            if (options.size() < 2 || options.size() > 26) throw new IllegalArgumentException("Cần 2–26 lựa chọn");
            if (type == Question.QuestionType.TRUE_FALSE && options.size() != 2)
                throw new IllegalArgumentException("Đúng/Sai cần đúng 2 lựa chọn");
            long correct = options.stream().filter(QuestionOption::isCorrect).count();
            if (type == Question.QuestionType.TRUE_FALSE_GROUP) {
                if (options.size() > 8) throw new IllegalArgumentException("Đúng/Sai nhiều ý cần 2–8 ý");
            } else if (type == Question.QuestionType.MULTIPLE_RESPONSE) {
                if (correct < 1) throw new IllegalArgumentException("Cần ít nhất 1 đáp án đúng");
            } else if (correct != 1)
                throw new IllegalArgumentException("Cần đúng 1 đáp án đúng");
            var positions = new HashSet<Short>();
            var contents = new HashSet<String>();
            for (var o : options) {
                text(o.getContent(), 5000, "Nội dung lựa chọn");
                if (o.getDisplayOrder() < 0 || !positions.add(o.getDisplayOrder()))
                    throw new IllegalArgumentException("Thứ tự lựa chọn phải không âm và không trùng");
                if (!contents.add(o.getContent().trim())) throw new IllegalArgumentException("Lựa chọn bị trùng");
            }
            if (q.getExpectedAnswer() != null && !q.getExpectedAnswer().isBlank())
                throw new IllegalArgumentException("Câu hỏi lựa chọn dùng đáp án trong options");
        } else {
            if (!options.isEmpty()) throw new IllegalArgumentException("Trả lời ngắn/tự luận không dùng options");
            if (q.getQuestionType() == Question.QuestionType.SHORT_ANSWER)
                text(q.getExpectedAnswer(), 10000, "Đáp án mong đợi");
        }
    }
    /** Types answered by picking options (the answer key lives in options). */
    public static boolean isChoice(Question.QuestionType t) {
        return t == Question.QuestionType.MULTIPLE_CHOICE || t == Question.QuestionType.TRUE_FALSE
                || t == Question.QuestionType.MULTIPLE_RESPONSE || t == Question.QuestionType.TRUE_FALSE_GROUP;
    }
    private static void text(String value, int max, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " không được trống");
        limit(value, max, name);
    }
    private static void limit(String value, int max, String name) {
        if (value != null && value.length() > max) throw new IllegalArgumentException(name + " quá dài (tối đa " + max + ")");
    }
}
