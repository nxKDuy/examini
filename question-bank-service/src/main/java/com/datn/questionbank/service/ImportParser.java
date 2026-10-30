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
        DUPLICATE_OPTION, OPTION_SEQUENCE_GAP, NO_ANSWER_ESSAY, CONTENT_TRUNCATED,
        /** OCR/text boundary doubts: a split or non-split that could be wrong is kept for review, never resolved by guessing. */
        QUESTION_SPLIT_UNCERTAIN, NUMBERING_GAP, POSSIBLE_MERGED_QUESTIONS,
        /** AI generation: every generated item carries AI_GENERATED; POSSIBLE_DUPLICATE marks a near-copy of an existing or sibling question. */
        AI_GENERATED, POSSIBLE_DUPLICATE,
        /** Quality gate (AiQuality): refers to a text the student never sees; an "all/none of the above" option;
         *  options too alike to be real alternatives; the stem gives the answer away. */
        SOURCE_REFERENCE, CATCH_ALL_OPTION, WEAK_DISTRACTORS, ANSWER_IN_STEM,
        /** A model re-solving the question without the key reached a different answer (AiKeyCheck). */
        KEY_DISPUTED }
    public record Option(String content, boolean correct, short displayOrder) {}
    public record Parsed(String content, QuestionType type, DifficultyLevel difficulty, List<Option> options,
            String expectedAnswer, String explanation, Integer page, SourceMethod method, double confidence,
            String rawText, List<Flag> flags, boolean reviewRequired) {}

    // OCR variants of "Câu": lost/changed diacritics (Cau, Càu, Cáu…); "l"/"I" read for the digit 1.
    private static final String CAU = "(?:c[aâàáạảãăắằấầẩẫậ]u|question|bài|bai)";
    private static final Pattern KEYWORD_START = Pattern.compile("^" + CAU + "\\s*(\\d{1,4}|[lI])\\s*[:.)\\-]?\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // Mid-line boundaries left by OCR line merges. Mid-line "Câu N" needs a capital C and a separator, so "xem câu 2" in prose is not a split.
    private static final Pattern INLINE_KEYWORD = Pattern.compile("(?<=\\S)\\s+(?=(?:C[aâàáạảãăắằấầẩẫậ]u|CÂU|CAU|Question|Bài)\\s*(\\d{1,4}|[lI])\\s*[:.)])");
    private static final Pattern INLINE_SECTION = Pattern.compile("(?<=\\S)\\s+(?=(?:(?i:đáp\\s*án|dap\\s*an|giải\\s*thích|lời\\s*giải|mức\\s*độ|độ\\s*khó)|Answer|Explanation|Difficulty)\\s*[:.\\-])", Pattern.UNICODE_CASE);
    private static final Pattern INLINE_OPTIONS = Pattern.compile("(?<=\\S)\\s+(?=A\\s*[.)]\\s+\\S.*?\\sB\\s*[.)]\\s+\\S)");
    private static final Pattern INLINE_NUMBER = Pattern.compile("(?<=\\S)\\s+(?=(\\d{1,3})\\s*[.)]\\s+(?![A-H]\\s*[.)])\\S)");
    private static final Pattern NUMBER_START = Pattern.compile("^(\\d{1,4})\\s*[.)]\\s+(.+)$");
    private static final Pattern STATEMENT = Pattern.compile("^([a-h])\\)\\s*(\\S.*)$");
    private static final Pattern OPTION = Pattern.compile("^([A-H])\\s*[.):]\\s*(.*)$");
    // OCR often drops the space after the marker: "A.90 B.100".
    private static final Pattern INLINE_OPTION = Pattern.compile("\\s+([B-H])\\s*[.)]\\s*(?=\\S)");
    private static final Pattern ANSWER = Pattern.compile("^(?:đáp\\s*án(?:\\s*đúng)?|dap\\s*an|đa|answer|ans)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPLANATION = Pattern.compile("^(?:giải\\s*thích|lời\\s*giải|hướng\\s*dẫn(?:\\s*giải)?|explanation)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Optional explicit type, needed for speaking and for essays given with a marking guide: "Loại: Nói". */
    private static final Pattern TYPE = Pattern.compile("^(?:loại(?:\\s*câu(?:\\s*hỏi)?)?|dạng(?:\\s*câu)?|type)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DIFFICULTY = Pattern.compile("^(?:mức\\s*độ|độ\\s*khó|difficulty)\\s*[:.\\-]\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANSWER_LETTERS = Pattern.compile("^([A-H])(?:\\s*(?:,|;|/|&|và|and)\\s*([A-H]))*\\s*[.)]?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SINGLE_LETTER = Pattern.compile("(?<!\\p{L})([A-Ha-h])(?!\\p{L})");
    private static final Pattern ANSWER_LEADING_LETTER = Pattern.compile("^([A-H])\\s*[.):]\\s+\\S.*$");

    private enum Section { CONTENT, OPTION, ANSWER, EXPLANATION }
    private static final class Draft {
        final List<Line> lines = new ArrayList<>();
        final StringBuilder content = new StringBuilder();
        final List<String[]> options = new ArrayList<>(); // [letter, text]
        final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        StringBuilder answer, explanation;
        String difficulty, typeHint;
        Section section = Section.CONTENT;
    }
    /** A physical line, or the tail of one split at a merged boundary; uncertain = numeric split guessed from sequence only. */
    private record Piece(Line line, boolean uncertain) {}

    public static List<Parsed> parse(List<Line> input, DifficultyLevel defaultDifficulty) {
        var lines = input.stream().map(l -> new Line(Normalizer.normalize(l.text(), Normalizer.Form.NFC), l.page(), l.method(), l.confidence())).toList();
        boolean keywords = lines.stream().anyMatch(l -> KEYWORD_START.matcher(l.text()).matches() || INLINE_KEYWORD.matcher(l.text()).find());
        var queue = new ArrayDeque<Piece>();
        lines.forEach(l -> queue.add(new Piece(l, false)));
        var drafts = new ArrayList<Draft>();
        Draft current = null;
        int last = 0;
        while (!queue.isEmpty()) {
            var piece = queue.poll();
            Line line = piece.line();
            String text = line.text();
            var cut = boundary(text, current, last);
            if (cut != null) {
                // Re-queue the tail (marked when the split rests only on number sequence); the head is handled now.
                String tail = text.substring(cut.index()).strip();
                queue.addFirst(new Piece(new Line(tail, line.page(), line.method(), line.confidence()), cut.numeric()));
                text = text.substring(0, cut.index()).strip();
                line = new Line(text, line.page(), line.method(), line.confidence());
            }
            Integer number = startNumber(text, keywords, current, last);
            boolean lettered = false;
            Matcher start = KEYWORD_START.matcher(text);
            if (number == null && current != null && NUMBER_START.matcher(text).matches() && (!keywords || blockDone(current)))
                // Numbered line out of sequence where a question could start: list item or lost question — keep as text, flag.
                current.flags.add(Flag.QUESTION_SPLIT_UNCERTAIN);
            if (number != null) {
                Matcher body = keywords && start.matches() ? start : NUMBER_START.matcher(text);
                body.matches();
                lettered = body == start && !body.group(1).chars().allMatch(Character::isDigit);
                current = new Draft();
                drafts.add(current);
                current.lines.add(line);
                current.content.append(body.group(2).strip());
                if (piece.uncertain() || lettered) current.flags.add(Flag.QUESTION_SPLIT_UNCERTAIN);
                if (last > 0 && number != last + 1) current.flags.add(Flag.NUMBERING_GAP);
                last = Math.max(last, number);
                continue;
            }
            if (current == null) continue; // header text before the first question
            current.lines.add(line);
            Matcher m;
            // THPT 2025 true/false statements are written "a) …", "b) …": read them as options (not inside the answer/explanation).
            if (current.section != Section.ANSWER && current.section != Section.EXPLANATION && (m = STATEMENT.matcher(text)).matches())
                text = Character.toUpperCase(m.group(1).charAt(0)) + ") " + m.group(2);
            if ((m = ANSWER.matcher(text)).matches()) {
                // A second answer means two questions were merged; keep the first, never pick one silently.
                if (current.answer != null) { current.flags.add(Flag.POSSIBLE_MERGED_QUESTIONS); current.section = Section.ANSWER; continue; }
                current.answer = new StringBuilder(m.group(1).strip()); current.section = Section.ANSWER;
            }
            else if ((m = EXPLANATION.matcher(text)).matches()) { current.explanation = new StringBuilder(m.group(1).strip()); current.section = Section.EXPLANATION; }
            else if ((m = DIFFICULTY.matcher(text)).matches()) current.difficulty = m.group(1).strip();
            else if ((m = TYPE.matcher(text)).matches()) current.typeHint = m.group(1).strip();
            else if (OPTION.matcher(text).matches() && current.section == Section.ANSWER) {
                // Options after the answer: the next question's header was lost. Flag; the options stay with this item for review.
                current.flags.add(Flag.POSSIBLE_MERGED_QUESTIONS);
                addOptions(current, text);
            }
            else if (current.section != Section.EXPLANATION && OPTION.matcher(text).matches()) {
                if (text.startsWith("A") && current.options.stream().anyMatch(o -> o[0].equals("A"))) current.flags.add(Flag.POSSIBLE_MERGED_QUESTIONS);
                addOptions(current, text);
            } else if (current.section == Section.ANSWER && !current.options.isEmpty()
                    && ANSWER_LETTERS.matcher(current.answer.toString().strip()).matches()) {
                // Text after a complete letter answer is a question whose header OCR lost; keep it in rawText, flag.
                current.flags.add(Flag.POSSIBLE_MERGED_QUESTIONS);
            } else {
                if (current.section == Section.EXPLANATION && text.startsWith("A") && OPTION.matcher(text).matches() && !current.options.isEmpty())
                    current.flags.add(Flag.POSSIBLE_MERGED_QUESTIONS);
                append(current, text);
            }
        }
        return drafts.stream().limit(MAX_ITEMS).map(d -> build(d, defaultDifficulty)).toList();
    }

    private static void addOptions(Draft d, String text) {
        for (String part : inlineOptions(text)) {
            Matcher o = OPTION.matcher(part);
            if (o.matches()) d.options.add(new String[] { o.group(1), o.group(2).strip() });
        }
        d.section = Section.OPTION;
    }

    /** Question number if this line starts a question, else null. Keyword docs: "Câu N"; "N." only right after a finished block, in sequence. */
    private static Integer startNumber(String text, boolean keywords, Draft current, int last) {
        Matcher k = KEYWORD_START.matcher(text);
        if (keywords && k.matches()) return number(k.group(1));
        Matcher n = NUMBER_START.matcher(text);
        if (!n.matches()) return null;
        int value = Integer.parseInt(n.group(1));
        if (!keywords) return current == null || value == last + 1 ? value : null;
        return current != null && value == last + 1 && blockDone(current) ? value : null;
    }
    private static int number(String digits) { return digits.chars().allMatch(Character::isDigit) ? Integer.parseInt(digits) : 1; }
    private static boolean blockDone(Draft d) { return d.answer != null || d.explanation != null; }

    private record Cut(int index, boolean numeric) {}
    /** Earliest place where a merged OCR line must be split, or null. */
    private static Cut boundary(String text, Draft current, int last) {
        Cut best = null;
        Matcher m = INLINE_KEYWORD.matcher(text);
        while (m.find()) if (m.group(1).chars().noneMatch(Character::isDigit) || number(m.group(1)) > last) { best = pick(best, m.end(), false); break; }
        if (current != null || best != null) { m = INLINE_SECTION.matcher(text); if (m.find()) best = pick(best, m.end(), false); }
        if (!OPTION.matcher(text).matches()) { m = INLINE_OPTIONS.matcher(text); if (m.find()) best = pick(best, m.end(), false); }
        Matcher answer = ANSWER.matcher(text);
        boolean answerLine = answer.matches();
        if (current != null && (blockDone(current) || answerLine)) {
            // Never inside a "Câu N." header (its own number) or before the answer body ("Đáp án: A 2. …" splits after A).
            Matcher header = KEYWORD_START.matcher(text);
            int from = header.matches() ? header.start(2) : answerLine ? answer.start(1) : 0;
            m = INLINE_NUMBER.matcher(text);
            while (m.find()) if (m.start() >= from && Integer.parseInt(m.group(1)) == last + 1) { best = pick(best, m.end(), true); break; }
        }
        return best;
    }
    private static Cut pick(Cut best, int index, boolean numeric) { return best == null || index < best.index() ? new Cut(index, numeric) : best; }

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
        var flags = EnumSet.copyOf(d.flags.isEmpty() ? EnumSet.noneOf(Flag.class) : d.flags);
        double penalty = 0;
        if (flags.contains(Flag.QUESTION_SPLIT_UNCERTAIN)) penalty += 0.2;
        if (flags.contains(Flag.NUMBERING_GAP)) penalty += 0.1;
        if (flags.contains(Flag.POSSIBLE_MERGED_QUESTIONS)) penalty += 0.4;
        String content = d.content.toString().strip();
        if (content.length() > 5000) { content = content.substring(0, 5000); flags.add(Flag.CONTENT_TRUNCATED); penalty += 0.2; }

        var letters = new ArrayList<String>();
        for (var o : d.options) letters.add(o[0].toUpperCase(Locale.ROOT));
        if (new HashSet<>(letters).size() != letters.size()) { flags.add(Flag.DUPLICATE_OPTION); penalty += 0.3; }
        for (int i = 0; i < letters.size(); i++)
            if (letters.get(i).charAt(0) != 'A' + i) { flags.add(Flag.OPTION_SEQUENCE_GAP); penalty += 0.2; break; }

        // OCR leaves stray marks after a short answer ("B_", "B |").
        String answer = d.answer == null ? null : d.answer.toString().strip().replaceAll("[\\s_|~`'\"]+$", "");
        if (answer != null && answer.isEmpty()) answer = null;
        var correct = new HashSet<Integer>();
        String expected = null;
        QuestionType type;
        QuestionType hinted = d.typeHint == null ? null : SheetImport.type(d.typeHint, !d.options.isEmpty());
        String guide = null;
        if (d.options.isEmpty() && (hinted == QuestionType.SPEAKING || hinted == QuestionType.ESSAY)) { type = hinted; guide = answer; }
        else if (!d.options.isEmpty() && (hinted == QuestionType.TRUE_FALSE_GROUP
                || (hinted == null && answer != null && d.options.size() >= 2 && !isTrueFalse(d.options) && SheetImport.truths(answer) != null && SheetImport.truths(answer).size() == d.options.size()))) {
            // THPT 2025 "Đúng/Sai" part: statements a) b) c) d), key "a) Đ b) S …"
            type = QuestionType.TRUE_FALSE_GROUP;
            var truths = SheetImport.truths(answer);
            if (truths == null || truths.size() != d.options.size()) { flags.add(Flag.MISSING_CORRECT_ANSWER); penalty += 0.2; }
            else for (int i = 0; i < truths.size(); i++) if (truths.get(i)) correct.add(i);
        }
        else if (!d.options.isEmpty()) {
            type = hinted == QuestionType.MULTIPLE_RESPONSE ? QuestionType.MULTIPLE_RESPONSE : isTrueFalse(d.options) ? QuestionType.TRUE_FALSE : QuestionType.MULTIPLE_CHOICE;
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
                if (new HashSet<>(chosen).size() > 1 && type != QuestionType.MULTIPLE_RESPONSE) { flags.add(Flag.MULTIPLE_ANSWERS); penalty += 0.2; }
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
        if (guide != null) explanation = (explanation == null ? "" : explanation + "\n") + "Gợi ý chấm: " + guide;
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
