package com.datn.questionbank.service;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.service.DocumentExtractor.Line;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * Template parser: "Câu N:" / A–H options / "Đáp án:" / "Giải thích:" / "Mức độ:".
 * Never guesses a missing answer; structural doubts become flags and lower the confidence.
 */
public final class ImportParser {
    private ImportParser() {}
    public static final double REVIEW_THRESHOLD = 0.80;
    public static final int MAX_ITEMS = 300;
    public enum Flag { OCR, LOW_CONFIDENCE, MISSING_CORRECT_ANSWER, ANSWER_NOT_IN_OPTIONS, MULTIPLE_ANSWERS,
        DUPLICATE_OPTION, OPTION_SEQUENCE_GAP, NO_ANSWER_ESSAY, CONTENT_TRUNCATED }
    public record Option(String content, boolean correct, short displayOrder) {}
    public record Parsed(String content, QuestionType type, DifficultyLevel difficulty, List<Option> options,
            String expectedAnswer, String explanation, Integer page, SourceMethod method, double confidence,
            String rawText, List<Flag> flags, boolean reviewRequired) {}

    private static final Pattern KEYWORD_START = Pattern.compile("^(?:câu|cau|question|bài|bai)\\s*(\\d{1,4})\\s*[:.)\\-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBER_START = Pattern.compile("^(\\d{1,4})\\s*[.)]\\s+(.+)$");
    private static final Pattern OPTION = Pattern.compile("^([A-H])\\s*[.):]\\s*(.*)$");
    private static final Pattern INLINE_OPTION = Pattern.compile("\\s+([B-H])\\s*[.)]\\s+");
    private static final Pattern ANSWER = Pattern.compile("^(?:đáp\\s*án(?:\\s*đúng)?|dap\\s*an|đa|answer|ans)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPLANATION = Pattern.compile("^(?:giải\\s*thích|lời\\s*giải|hướng\\s*dẫn(?:\\s*giải)?|explanation)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DIFFICULTY = Pattern.compile("^(?:mức\\s*độ|độ\\s*khó|difficulty)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANSWER_LETTERS = Pattern.compile("^([A-H])(?:\\s*(?:,|;|/|&|và|and)\\s*([A-H]))*\\s*[.)]?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SINGLE_LETTER = Pattern.compile("(?<!\\p{L})([A-Ha-h])(?!\\p{L})");
    private static final Pattern ANSWER_LEADING_LETTER = Pattern.compile("^([A-H])\\s*[.):]\\s+\\S.*$");

    private enum Section { CONTENT, OPTION, ANSWER, EXPLANATION }
    private static final class Draft {
        final List<Line> lines = new ArrayList<>();
        final StringBuilder content = new StringBuilder();
        final List<String[]> options = new ArrayList<>(); // [letter, text]
        StringBuilder answer, explanation;
        String difficulty;
        Section section = Section.CONTENT;
    }

    public static List<Parsed> parse(List<Line> input, DifficultyLevel defaultDifficulty) {
        var lines = input.stream().map(l -> new Line(Normalizer.normalize(l.text(), Normalizer.Form.NFC), l.page(), l.method(), l.confidence())).toList();
        boolean keywords = lines.stream().anyMatch(l -> KEYWORD_START.matcher(l.text()).matches());
        var drafts = new ArrayList<Draft>();
        Draft current = null;
        for (Line line : lines) {
            String text = line.text();
            Matcher start = (keywords ? KEYWORD_START : NUMBER_START).matcher(text);
            if (start.matches()) {
                current = new Draft();
                drafts.add(current);
                current.lines.add(line);
                current.content.append(start.group(2).strip());
                continue;
            }
            if (current == null) continue; // header text before the first question
            current.lines.add(line);
            Matcher m;
            if ((m = ANSWER.matcher(text)).matches()) { current.answer = new StringBuilder(m.group(1).strip()); current.section = Section.ANSWER; }
            else if ((m = EXPLANATION.matcher(text)).matches()) { current.explanation = new StringBuilder(m.group(1).strip()); current.section = Section.EXPLANATION; }
            else if ((m = DIFFICULTY.matcher(text)).matches()) current.difficulty = m.group(1).strip();
            else if (current.section != Section.ANSWER && current.section != Section.EXPLANATION && OPTION.matcher(text).matches()) {
                for (String part : inlineOptions(text)) {
                    Matcher o = OPTION.matcher(part);
                    if (o.matches()) current.options.add(new String[] { o.group(1), o.group(2).strip() });
                }
                current.section = Section.OPTION;
            } else append(current, text);
        }
        return drafts.stream().limit(MAX_ITEMS).map(d -> build(d, defaultDifficulty)).toList();
    }

    /** "A. 1   B. 2   C. 3" on one line → separate options. */
    private static List<String> inlineOptions(String text) {
        var parts = new ArrayList<String>();
        Matcher m = INLINE_OPTION.matcher(text);
        int from = 0;
        while (m.find()) { parts.add(text.substring(from, m.start()).strip()); from = m.start() + (m.group().length() - m.group().stripLeading().length()); }
        parts.add(text.substring(from).strip());
        return parts;
    }

    private static void append(Draft d, String text) {
        switch (d.section) {
            case CONTENT -> d.content.append(d.content.isEmpty() ? "" : "\n").append(text);
            case OPTION -> { var last = d.options.getLast(); last[1] = (last[1] + " " + text).strip(); }
            case ANSWER -> d.answer.append(d.answer.isEmpty() ? "" : " ").append(text);
            case EXPLANATION -> d.explanation.append(d.explanation.isEmpty() ? "" : "\n").append(text);
        }
    }

    private static Parsed build(Draft d, DifficultyLevel defaultDifficulty) {
        var flags = EnumSet.noneOf(Flag.class);
        double penalty = 0;
        String content = d.content.toString().strip();
        if (content.length() > 5000) { content = content.substring(0, 5000); flags.add(Flag.CONTENT_TRUNCATED); penalty += 0.2; }

        var letters = new ArrayList<String>();
        for (var o : d.options) letters.add(o[0].toUpperCase(Locale.ROOT));
        if (new HashSet<>(letters).size() != letters.size()) { flags.add(Flag.DUPLICATE_OPTION); penalty += 0.3; }
        for (int i = 0; i < letters.size(); i++)
            if (letters.get(i).charAt(0) != 'A' + i) { flags.add(Flag.OPTION_SEQUENCE_GAP); penalty += 0.2; break; }

        String answer = d.answer == null ? null : d.answer.toString().strip();
        if (answer != null && answer.isEmpty()) answer = null;
        var correct = new HashSet<Integer>();
        String expected = null;
        QuestionType type;
        if (!d.options.isEmpty()) {
            type = isTrueFalse(d.options) ? QuestionType.TRUE_FALSE : QuestionType.MULTIPLE_CHOICE;
            if (d.options.size() < 2) penalty += 0.3;
            if (answer == null) { flags.add(Flag.MISSING_CORRECT_ANSWER); penalty += 0.2; }
            else {
                Matcher only = ANSWER_LETTERS.matcher(answer), leading = ANSWER_LEADING_LETTER.matcher(answer);
                var chosen = new ArrayList<String>();
                if (only.matches()) { Matcher letter = SINGLE_LETTER.matcher(answer); while (letter.find()) chosen.add(letter.group(1).toUpperCase(Locale.ROOT)); }
                else if (leading.matches()) chosen.add(leading.group(1));
                else for (int i = 0; i < d.options.size(); i++)
                    if (d.options.get(i)[1].equalsIgnoreCase(answer)) chosen.add(letters.get(i));
                if (chosen.isEmpty() && type == QuestionType.TRUE_FALSE) {
                    for (int i = 0; i < d.options.size(); i++) if (truth(d.options.get(i)[1]) != null && truth(d.options.get(i)[1]).equals(truth(answer))) chosen.add(letters.get(i));
                }
                for (String letter : chosen) {
                    int index = letters.indexOf(letter);
                    if (index >= 0) correct.add(index);
                }
                if (correct.isEmpty() || correct.size() != new HashSet<>(chosen).size()) { flags.add(Flag.ANSWER_NOT_IN_OPTIONS); penalty += 0.3; }
                if (new HashSet<>(chosen).size() > 1) { flags.add(Flag.MULTIPLE_ANSWERS); penalty += 0.2; }
            }
        } else if (answer != null) { type = QuestionType.SHORT_ANSWER; expected = answer; }
        else { type = QuestionType.ESSAY; flags.add(Flag.NO_ANSWER_ESSAY); penalty += 0.2; }

        var options = new ArrayList<Option>();
        for (int i = 0; i < d.options.size(); i++) options.add(new Option(d.options.get(i)[1], correct.contains(i), (short) i));

        boolean ocr = d.lines.stream().anyMatch(l -> l.method() == SourceMethod.OCR);
        if (ocr) flags.add(Flag.OCR);
        double base = ocr ? d.lines.stream().filter(l -> l.confidence() != null).mapToDouble(Line::confidence).average().orElse(0) / 100.0 : 1.0;
        double confidence = Math.round(Math.max(0, Math.min(1, base - penalty)) * 100) / 100.0;
        if (confidence < REVIEW_THRESHOLD) flags.add(Flag.LOW_CONFIDENCE);
        boolean review = ocr || flags.stream().anyMatch(f -> f != Flag.MISSING_CORRECT_ANSWER);
        String explanation = d.explanation == null || d.explanation.isEmpty() ? null : d.explanation.toString().strip();
        String raw = String.join("\n", d.lines.stream().map(Line::text).toList());
        return new Parsed(content, type, difficulty(d.difficulty, defaultDifficulty), options, expected, explanation,
                d.lines.getFirst().page(), ocr ? SourceMethod.OCR : SourceMethod.TEXT, confidence,
                raw.length() > 10000 ? raw.substring(0, 10000) : raw, List.copyOf(flags), review);
    }

    private static boolean isTrueFalse(List<String[]> options) {
        return options.size() == 2 && truth(options.get(0)[1]) != null && truth(options.get(1)[1]) != null
                && !truth(options.get(0)[1]).equals(truth(options.get(1)[1]));
    }
    private static Boolean truth(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]$", "");
        if (t.equals("đúng") || t.equals("true") || t.equals("đ")) return true;
        if (t.equals("sai") || t.equals("false") || t.equals("s")) return false;
        return null;
    }
    private static DifficultyLevel difficulty(String value, DifficultyLevel fallback) {
        if (value == null) return fallback;
        String v = value.toLowerCase(Locale.ROOT);
        if (v.startsWith("dễ") || v.startsWith("easy") || v.startsWith("nhận biết")) return DifficultyLevel.EASY;
        if (v.startsWith("trung bình") || v.startsWith("medium") || v.startsWith("thông hiểu")) return DifficultyLevel.MEDIUM;
        if (v.startsWith("khó") || v.startsWith("hard") || v.startsWith("vận dụng")) return DifficultyLevel.HARD;
        return fallback;
    }
}
