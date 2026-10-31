package com.datn.questionbank.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text conventions of the two structured objective types (contracts/question-media-and-skills.md).
 * FILL_BLANK: gaps are runs of three or more underscores in the stem; option i lists the accepted answers of gap i,
 * alternatives separated by "|". MATCHING: one option per pair, written "left => right".
 */
public final class StructuredTypes {
    private StructuredTypes() {}
    public static final Pattern BLANK = Pattern.compile("_{3,}");
    public static final String ARROW = "=>";
    public static final int MAX_BLANKS = 20, MAX_PAIRS = 10;
    /** Arrows people type in Word/Excel, normalised to "=>". */
    private static final Pattern ANY_ARROW = Pattern.compile("\\s*(?:=>|->|→|⇒|⟶|—>|–>)\\s*");

    public static int blanks(String content) {
        if (content == null) return 0;
        Matcher m = BLANK.matcher(content);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** Accepted answers of one gap, trimmed, blanks dropped. */
    public static List<String> alternatives(String option) {
        if (option == null) return List.of();
        return Arrays.stream(option.split("\\|")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** {left, right} of a "left => right" pair, or null when the text is not a pair. */
    public static String[] pair(String option) {
        if (option == null) return null;
        int i = option.indexOf(ARROW);
        if (i < 0) return null;
        String left = option.substring(0, i).strip(), right = option.substring(i + ARROW.length()).strip();
        return left.isEmpty() || right.isEmpty() ? null : new String[]{left, right};
    }

    /** "Hà Nội → Việt Nam" or "Hà Nội -> Việt Nam" → "Hà Nội => Việt Nam"; null when no arrow joins two sides. */
    public static String normalizePair(String raw) {
        if (raw == null) return null;
        Matcher m = ANY_ARROW.matcher(raw);
        if (!m.find()) return null;
        String left = raw.substring(0, m.start()).strip(), right = raw.substring(m.end()).strip();
        return left.isEmpty() || right.isEmpty() ? null : left + " " + ARROW + " " + right;
    }

    /** True when every text is a pair (after normalising arrows) and there are at least two. */
    public static boolean allPairs(List<String> texts) {
        return texts.size() >= 2 && texts.stream().allMatch(t -> normalizePair(t) != null);
    }

    /** Gap answers written in one cell: "Hà Nội; 1945" (gaps separated by ";" or new lines). */
    public static List<String> gapAnswers(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var out = new ArrayList<String>();
        for (String part : raw.split("[;\\n]")) if (!part.isBlank()) out.add(part.strip());
        return out;
    }
}
