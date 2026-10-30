package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ImportParser.Flag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based quality gate for generated questions, run before any teacher sees them. No model, no network: string
 * checks with high precision whose findings only add review flags (nothing is rewritten or dropped).
 *
 * <p>Ideas adapted from open-source question generators: SemanticQAGen's leak filter (a stand-alone question must
 * not point at a "passage" the student never sees) and diversity check (near-duplicate questions), and Questgen's
 * distractor filtering (options too close to each other or to the key are not real alternatives).
 */
public final class AiQuality {
    private AiQuality() {}

    /** Near-duplicate threshold on character-bigram Dice similarity of normalised text. */
    static final double DUPLICATE_SIMILARITY = 0.88;
    /** Options this similar (normalised edit similarity) are not distinct alternatives. */
    static final double OPTION_SIMILARITY = 0.9;
    /** A stem longer than this carries its own text, so "theo đoạn văn" refers to something the student sees. */
    static final int STEM_WITH_OWN_TEXT = 400;

    private static final Pattern SOURCE_REFERENCE = Pattern.compile(
            "\\b(theo|trong|dua vao|can cu vao|tu) (doan van|van ban|bai doc|tai lieu|doan trich|bai viet|ngu lieu)( (tren|da cho|vua doc|sau))?\\b"
                    + "|\\b(doan van|van ban|bai doc|tai lieu|doan trich) (tren|vua doc)\\b"
                    + "|\\btac gia (cho rang|nhan dinh|khang dinh|neu)\\b"
                    + "|\\baccording to the (passage|text|document|excerpt|article|author|reading|material)\\b"
                    + "|\\b(the|this) (passage|text|document|excerpt|article|author) (states?|says?|mentions?|describes?|suggests?|argues?|claims?)\\b"
                    + "|\\bas (mentioned|described|stated|discussed) (above|earlier|in the (passage|text))\\b"
                    + "|\\bin (the|this) (passage|text|document|excerpt)\\b");
    private static final Pattern CATCH_ALL = Pattern.compile(
            "^(ca )?tat ca (cac )?(dap an|phuong an|y|cau)( tren| deu dung| deu sai)?$|^ca [a-d](,? [a-d])* (va|lan) [a-d]( deu dung)?$"
                    + "|^khong (co )?(dap an|phuong an|y) nao( dung)?$|^(all|none|both) of the (above|options)$|^both [a-d] and [a-d]$");

    /** Flags for one draft; {@code siblings} and {@code existing} are normalised keys of other questions. */
    static Set<Flag> check(String content, QuestionType type, List<String> options, List<Boolean> correct,
                           List<String> earlierInBatch, List<String> existing) {
        var flags = EnumSet.noneOf(Flag.class);
        String stem = AiDrafts.key(content);
        if (content.length() <= STEM_WITH_OWN_TEXT && SOURCE_REFERENCE.matcher(stem).find()) flags.add(Flag.SOURCE_REFERENCE);
        if (nearDuplicate(stem, earlierInBatch) || nearDuplicate(stem, existing)) flags.add(Flag.POSSIBLE_DUPLICATE);
        if (options.isEmpty() || type == QuestionType.TRUE_FALSE_GROUP) return flags;

        var keys = options.stream().map(AiDrafts::key).toList();
        if (keys.stream().anyMatch(k -> CATCH_ALL.matcher(k).find())) flags.add(Flag.CATCH_ALL_OPTION);
        if (weakDistractors(options.stream().filter(AiQuality::wordy).map(AiQuality::light).toList())) flags.add(Flag.WEAK_DISTRACTORS);
        if (type == QuestionType.MULTIPLE_CHOICE && answerInStem(stem, keys, correct)) flags.add(Flag.ANSWER_IN_STEM);
        return flags;
    }

    static boolean nearDuplicate(String key, List<String> others) {
        if (key.length() < 20) return false; // short stems ("2 + 2 = ?") are legitimately alike
        for (String o : others) if (o.length() >= 20 && dice(key, o) >= DUPLICATE_SIMILARITY) return true;
        return false;
    }

    /**
     * Two different worded options that read almost the same, or one wholly contained in another. Options with digits
     * or math symbols are left out: formulas and numbers legitimately differ by one character ("x = 1" / "x = −1").
     */
    static boolean weakDistractors(List<String> texts) {
        for (int i = 0; i < texts.size(); i++)
            for (int j = i + 1; j < texts.size(); j++) {
                String a = texts.get(i), b = texts.get(j);
                if (a.isEmpty() || b.isEmpty() || a.equals(b)) continue; // identical options are DUPLICATE_OPTION
                if (Math.min(a.length(), b.length()) >= 16 && similarity(a, b) >= OPTION_SIMILARITY) return true;
                String shorter = a.length() <= b.length() ? a : b, longer = shorter == a ? b : a;
                if (shorter.length() >= 6 && (" " + longer + " ").contains(" " + shorter + " ")) return true;
            }
        return false;
    }

    /** Plain words only: no digits and no math/chemistry symbols. */
    static boolean wordy(String s) { return s != null && !s.matches("(?s).*[\\p{N}=+\\-−×÷<>≤≥√^²³∞∫∑(){}\\[\\]/].*"); }

    /** Case- and spacing-insensitive form that keeps accents and symbols ("x = 1" ≠ "x = -1", "Huế" ≠ "Hue"). */
    static String light(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** The correct option's words appear verbatim in the stem while no distractor does: the stem gives it away. */
    static boolean answerInStem(String stem, List<String> keys, List<Boolean> correct) {
        String padded = " " + stem + " ";
        boolean keyed = false;
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (Boolean.TRUE.equals(correct.get(i))) keyed |= k.length() >= 6 && !k.matches("[0-9 ]+") && padded.contains(" " + k + " ");
            else if (k.length() >= 2 && padded.contains(" " + k + " ")) return false; // a distractor is named too: a real choice
        }
        return keyed;
    }

    /** Sørensen–Dice coefficient over character bigrams. */
    static double dice(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.length() < 2 || b.length() < 2) return 0;
        var bigrams = new ArrayList<String>();
        for (int i = 0; i < a.length() - 1; i++) bigrams.add(a.substring(i, i + 2));
        int matches = 0, total = bigrams.size() + b.length() - 1;
        for (int i = 0; i < b.length() - 1; i++) {
            String g = b.substring(i, i + 2);
            if (bigrams.remove(g)) matches++;
        }
        return 2.0 * matches / total;
    }

    /** 1 − normalised Levenshtein distance. */
    static double similarity(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }
}
