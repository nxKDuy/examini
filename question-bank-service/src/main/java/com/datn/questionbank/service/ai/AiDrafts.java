package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.service.ImportParser;
import com.datn.questionbank.service.ImportParser.Flag;
import java.text.Normalizer;
import java.util.*;

/**
 * Turns raw AI candidates into staged import items. Same principle as document import: nothing is
 * guessed. A missing or ambiguous answer key stays missing and is flagged; every item needs review.
 */
public final class AiDrafts {
    private AiDrafts() {}
    /** AI output starts below the review threshold: it is a draft, never trusted as-is. */
    public static final double BASE_CONFIDENCE = 0.70;
    private static final double PENALTY = 0.15;
    private static final int MAX_CONTENT = 5000, MAX_OPTION = 5000, MAX_TEXT = 10000, MAX_OPTIONS = 26;

    public record Drafts(List<ImportParser.Parsed> items, List<String> warnings) {}

    /**
     * @param existing contents of questions already in the bank/category, used for duplicate flags only
     */
    public static Drafts build(List<AiQuestionGenerator.Candidate> candidates, int requested,
                               DifficultyLevel fallback, Collection<String> existing) {
        var seen = new HashSet<String>();
        for (String e : existing) if (e != null) seen.add(key(e));
        var out = new ArrayList<ImportParser.Parsed>();
        var warnings = new ArrayList<String>();
        int dropped = 0, extra = 0;
        for (var c : candidates) {
            if (c == null || blank(c.content())) { dropped++; continue; }
            if (out.size() >= requested) { extra++; continue; }
            var flags = EnumSet.of(Flag.AI_GENERATED);
            String content = c.content().strip();
            if (content.length() > MAX_CONTENT) { content = content.substring(0, MAX_CONTENT); flags.add(Flag.CONTENT_TRUNCATED); }

            var rawOptions = c.options() == null ? List.<AiQuestionGenerator.CandidateOption>of()
                    : c.options().stream().filter(o -> o != null && !blank(o.content())).toList();
            QuestionType type = type(c.type(), rawOptions, c.expectedAnswer());
            boolean choice = type == QuestionType.MULTIPLE_CHOICE || type == QuestionType.TRUE_FALSE;

            var options = new ArrayList<ImportParser.Option>();
            String expected = blank(c.expectedAnswer()) ? null : clip(c.expectedAnswer().strip(), MAX_TEXT);
            if (choice) {
                var texts = new HashSet<String>();
                short order = 0;
                for (var o : rawOptions) {
                    if (options.size() >= MAX_OPTIONS) { flags.add(Flag.CONTENT_TRUNCATED); break; }
                    String text = clip(o.content().strip(), MAX_OPTION);
                    if (!texts.add(key(text))) flags.add(Flag.DUPLICATE_OPTION);
                    options.add(new ImportParser.Option(text, Boolean.TRUE.equals(o.correct()), order++));
                }
                long correct = options.stream().filter(ImportParser.Option::correct).count();
                if (correct == 0) flags.add(Flag.MISSING_CORRECT_ANSWER);
                if (correct > 1) flags.add(Flag.MULTIPLE_ANSWERS);
                expected = null; // choice questions keep their key in options only
            } else if (type == QuestionType.ESSAY && expected == null) {
                flags.add(Flag.NO_ANSWER_ESSAY);
            }

            if (!seen.add(key(content))) flags.add(Flag.POSSIBLE_DUPLICATE);

            String explanation = blank(c.explanation()) ? null : clip(c.explanation().strip(), MAX_TEXT);
            long problems = flags.stream().filter(f -> f != Flag.AI_GENERATED).count();
            double confidence = Math.max(0, BASE_CONFIDENCE - PENALTY * problems);
            String raw = c.raw() == null || c.raw().isBlank() ? content : clip(c.raw(), MAX_TEXT);
            out.add(new ImportParser.Parsed(content, type, difficulty(c.difficulty(), fallback), options, expected, explanation,
                    null, SourceMethod.AI, confidence, raw, List.copyOf(flags), true));
        }
        if (dropped > 0) warnings.add("Bỏ qua " + dropped + " câu AI trả về không có nội dung");
        if (extra > 0) warnings.add("AI trả về thừa " + extra + " câu; chỉ giữ " + requested + " câu đầu");
        if (out.size() < requested) warnings.add("AI chỉ tạo được " + out.size() + "/" + requested + " câu");
        return new Drafts(out, warnings);
    }

    /** Declared type when valid; otherwise inferred from shape, never from content guesses. */
    static QuestionType type(String declared, List<AiQuestionGenerator.CandidateOption> options, String expected) {
        if (declared != null) {
            try { return QuestionType.valueOf(declared.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
        }
        if (!options.isEmpty()) return options.size() == 2 && options.stream().allMatch(o -> isTrueFalse(o.content()))
                ? QuestionType.TRUE_FALSE : QuestionType.MULTIPLE_CHOICE;
        return blank(expected) ? QuestionType.ESSAY : QuestionType.SHORT_ANSWER;
    }
    private static boolean isTrueFalse(String s) {
        return Set.of("đúng", "sai", "true", "false").contains(s.strip().toLowerCase(Locale.ROOT));
    }
    static DifficultyLevel difficulty(String value, DifficultyLevel fallback) {
        if (value != null) {
            try { return DifficultyLevel.valueOf(value.strip().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
        }
        return fallback == null ? DifficultyLevel.MEDIUM : fallback;
    }
    /** Duplicate key: case-, accent-, punctuation- and whitespace-insensitive. */
    public static String key(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'd');
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
