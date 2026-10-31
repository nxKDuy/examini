package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rule-based generator that needs no model and no network: it reads the knowledge text, picks sentences that carry a
 * checkable fact (a number, a proper name or a salient keyword) and turns them into cloze multiple-choice, true/false,
 * fill-in and short essay questions. Distractors come from facts of the same kind in the same material. Quality is
 * modest by design; every item still goes through teacher review. It is the last link of the fallback chain.
 */
@Component
public class LocalQuestionGenerator implements AiProvider {
    static final String MODEL = "local/rule-based";
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?…])\\s+|\\n+");
    private static final Pattern HEADER = Pattern.compile("^\\[\\d+]\\s*[^:\\n]{0,120}:\\s*");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])\\d+(?:[.,]\\d+)*(?:\\s?%)?(?![\\p{L}\\d])");
    private static final Pattern PROPER = Pattern.compile("(?<![\\p{L}])\\p{Lu}[\\p{Ll}\\p{M}]+(?:\\s+\\p{Lu}[\\p{Ll}\\p{M}]+){0,3}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]{2,}");
    private static final Set<String> STOP = Set.of(
            // Vietnamese function words (accent-folded)
            "va", "la", "cua", "cac", "nhung", "mot", "nay", "do", "duoc", "trong", "cho", "voi", "khi", "thi", "de", "co", "khong",
            "nguoi", "tu", "den", "ra", "vao", "len", "nhu", "theo", "tren", "duoi", "sau", "truoc", "cung", "rat", "nhieu", "hon",
            "da", "dang", "se", "bi", "boi", "vi", "neu", "hay", "hoac", "ma", "nen", "con", "lai", "ve", "tai", "o", "noi", "nam",
            "ngay", "thang", "moi", "tat", "ca", "nhat", "viec", "su", "phan", "dieu", "cach", "nhau", "minh", "chung",
            // English
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "which", "into", "their", "there", "have", "has");

    @Override public String name() { return "local"; }
    @Override public boolean llm() { return false; }
    @Override public boolean available() { return true; }
    @Override public String model() { return MODEL; }

    record Fact(String sentence, String term, Kind kind, int start, int end) {}
    enum Kind { NUMBER, NAME, KEYWORD }

    @Override
    public Result generate(Prompt p) {
        if (p.material() == null || p.material().isBlank())
            throw new GenerationException("Bộ sinh nội bộ cần tài liệu kiến thức: hãy dán nội dung bài học hoặc bật tìm kiến thức tự động");
        boolean vi = !"en".equalsIgnoreCase(p.language());
        var sentences = sentences(p.material());
        var keywords = keywords(p.material());
        var pools = new EnumMap<Kind, LinkedHashSet<String>>(Kind.class);
        for (Kind k : Kind.values()) pools.put(k, new LinkedHashSet<>());
        var facts = new ArrayList<Fact>();
        var topicTokens = tokens(p.topic());
        for (String s : sentences) {
            var f = fact(s, keywords, topicTokens);
            if (f == null) continue;
            facts.add(f);
        }
        for (String s : sentences) collect(s, keywords, pools);
        if (facts.isEmpty()) throw new GenerationException("Tài liệu quá ngắn hoặc không có thông tin để hỏi (cần câu có số liệu, tên riêng hoặc thuật ngữ)");

        // Most relevant facts first: overlap with the topic, then numbers/names (more checkable) before keywords.
        var topic = tokens(p.topic());
        facts.sort(Comparator.comparingInt((Fact f) -> -overlap(tokens(f.sentence()), topic)).thenComparing(f -> f.kind().ordinal()));
        var rnd = new Random(Objects.hash(p.topic(), p.material().length()));
        var types = p.types() == null || p.types().isEmpty() ? List.of(QuestionType.MULTIPLE_CHOICE) : p.types();
        var out = new ArrayList<Candidate>();
        var usedSentences = new HashSet<String>();
        int t = 0;
        for (Fact f : facts) {
            if (out.size() >= p.count()) break;
            if (!usedSentences.add(f.sentence())) continue;
            QuestionType type = types.get(t % types.size());
            Candidate c = switch (type) {
                case MULTIPLE_CHOICE -> multipleChoice(f, pools, rnd, vi, p);
                case TRUE_FALSE -> trueFalse(f, pools, rnd, vi, p);
                case SHORT_ANSWER -> fillIn(f, vi, p);
                case ESSAY -> essay(f, vi, p);
                case SPEAKING -> null; // speaking prompts come from AI providers, not the rule-based generator
                case TRUE_FALSE_GROUP, MULTIPLE_RESPONSE -> statements(type, f, facts, usedSentences, pools, rnd, vi, p);
                case FILL_BLANK -> cloze(f, facts, usedSentences, vi, p);
                case MATCHING -> matching(f, facts, usedSentences, rnd, vi, p);
            };
            if (c == null) c = type == QuestionType.MULTIPLE_CHOICE && types.contains(QuestionType.TRUE_FALSE) ? trueFalse(f, pools, rnd, vi, p) : fillIn(f, vi, p);
            if (c == null || !types.contains(QuestionType.valueOf(c.type()))) { usedSentences.remove(f.sentence()); continue; }
            out.add(c); t++;
        }
        var warnings = new ArrayList<String>();
        warnings.add(vi ? "Câu hỏi do bộ sinh nội bộ tạo theo mẫu từ tài liệu (không dùng AI); hãy kiểm tra cách diễn đạt."
                : "Questions were built from the material by the local rule-based generator (no AI); check the wording.");
        return new Result(out, warnings, MODEL);
    }

    // ── Text analysis ─────────────────────────────────────────────────────────────

    static List<String> sentences(String material) {
        var out = new ArrayList<String>();
        for (String raw : SENTENCE.split(material)) {
            String s = HEADER.matcher(raw.strip()).replaceFirst("").strip();
            int words = s.isEmpty() ? 0 : s.split("\\s+").length;
            if (s.length() >= 30 && s.length() <= 320 && words >= 6) out.add(s);
        }
        return out;
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'd').toLowerCase(Locale.ROOT);
    }
    static Set<String> tokens(String s) {
        var out = new HashSet<String>();
        if (s == null) return out;
        Matcher m = WORD.matcher(s);
        while (m.find()) { String w = fold(m.group()); if (!STOP.contains(w)) out.add(w); }
        return out;
    }
    private static int overlap(Set<String> a, Set<String> b) { int n = 0; for (String x : a) if (b.contains(x)) n++; return n; }

    /** Salient two-word phrases and long words, by frequency (Vietnamese terms are mostly multi-syllable). */
    static List<String> keywords(String material) {
        var freq = new HashMap<String, Integer>();
        var surface = new HashMap<String, String>();
        var words = new ArrayList<String>();
        Matcher m = WORD.matcher(material);
        while (m.find()) words.add(m.group());
        for (int i = 0; i < words.size(); i++) {
            String a = words.get(i), fa = fold(a);
            if (!STOP.contains(fa) && a.length() >= 5) { freq.merge(fa, 1, Integer::sum); surface.putIfAbsent(fa, a.toLowerCase(Locale.ROOT)); }
            if (i + 1 < words.size()) {
                String b = words.get(i + 1), fb = fold(b);
                if (!STOP.contains(fa) && !STOP.contains(fb)) {
                    String key = fa + " " + fb;
                    freq.merge(key, 1, Integer::sum);
                    surface.putIfAbsent(key, (a + " " + b).toLowerCase(Locale.ROOT));
                }
            }
        }
        // A phrase must recur (2+ times; single words 3+) to count as a term: one-off syllable pairs are noise.
        return freq.entrySet().stream().filter(e -> e.getValue() >= (e.getKey().contains(" ") ? 2 : 3))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(40).map(e -> surface.get(e.getKey())).toList();
    }

    /** The most checkable fact in a sentence: a number, else a proper name not opening the sentence, else a keyword. */
    static Fact fact(String s, List<String> keywords) { return fact(s, keywords, Set.of()); }
    static Fact fact(String s, List<String> keywords, Set<String> topicTokens) {
        Matcher n = NUMBER.matcher(s);
        while (n.find()) {
            String v = n.group().strip();
            if (v.replaceAll("\\D", "").length() >= 1 && !(v.length() == 1 && n.start() == 0)) return new Fact(s, v, Kind.NUMBER, n.start(), n.start() + v.length());
        }
        Matcher pn = PROPER.matcher(s);
        while (pn.find()) {
            if (pn.start() == 0 && !pn.group().contains(" ")) continue; // sentence-initial capital is not a name
            if (STOP.contains(fold(pn.group()))) continue;
            return new Fact(s, pn.group(), Kind.NAME, pn.start(), pn.end());
        }
        String lower = s.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (!topicTokens.isEmpty() && topicTokens.containsAll(tokens(k))) continue; // the topic itself is too easy an answer
            int at = lower.indexOf(k);
            if (at >= 0 && (at == 0 || !Character.isLetter(lower.charAt(at - 1))) && (at + k.length() == lower.length() || !Character.isLetter(lower.charAt(at + k.length()))))
                return new Fact(s, s.substring(at, at + k.length()), Kind.KEYWORD, at, at + k.length());
        }
        return null;
    }

    private static void collect(String s, List<String> keywords, Map<Kind, LinkedHashSet<String>> pools) {
        Matcher n = NUMBER.matcher(s); while (n.find()) pools.get(Kind.NUMBER).add(n.group().strip());
        Matcher pn = PROPER.matcher(s); while (pn.find()) if (!(pn.start() == 0 && !pn.group().contains(" ")) && !STOP.contains(fold(pn.group()))) pools.get(Kind.NAME).add(pn.group());
        String lower = s.toLowerCase(Locale.ROOT);
        for (String k : keywords) if (lower.contains(k)) pools.get(Kind.KEYWORD).add(k);
    }

    /** Up to {@code n} wrong answers of the same kind; numbers can be derived when the material has too few. */
    static List<String> distractors(Fact f, Map<Kind, LinkedHashSet<String>> pools, int n, Random rnd) {
        String key = fold(f.term());
        var pool = new ArrayList<String>();
        String sentence = fold(f.sentence());
        // Same kind, different value, and not already in the sentence (a swap must change the fact).
        for (String x : pools.get(f.kind()))
            if (!fold(x).equals(key) && !sentence.contains(fold(x)) && (f.kind() != Kind.NUMBER || shape(x).equals(shape(f.term())))) pool.add(x);
        Collections.shuffle(pool, rnd);
        var out = new ArrayList<String>();
        for (String x : pool) { if (out.size() >= n) break; if (out.stream().noneMatch(o -> fold(o).equals(fold(x)))) out.add(x); }
        if (f.kind() == Kind.NUMBER) for (String x : numberVariants(f.term())) { if (out.size() >= n) break; if (!fold(x).equals(key) && !out.contains(x)) out.add(x); }
        return out;
    }

    /** Numbers only replace numbers of the same kind: percentages, years, decimals or plain counts. */
    static String shape(String n) {
        String t = n.strip();
        if (t.endsWith("%")) return "percent";
        if (t.matches("\\d{4}")) { int v = Integer.parseInt(t); if (v >= 1000 && v <= 2100) return "year"; }
        if (t.matches("\\d+,\\d{1,2}") || t.matches("\\d+\\.\\d{1,2}")) return "decimal";
        return "count";
    }

    static List<String> numberVariants(String term) {
        boolean pct = term.endsWith("%");
        String digits = term.replace("%", "").strip();
        boolean decimalComma = digits.matches("\\d+,\\d{1,2}");
        String plain = decimalComma ? digits.replace(',', '.') : digits.replaceAll("[.,](?=\\d{3}(\\D|$))", "");
        double v;
        try { v = Double.parseDouble(plain.replace(',', '.')); } catch (NumberFormatException e) { return List.of(); }
        boolean integer = v == Math.rint(v);
        boolean year = integer && v >= 1000 && v <= 2100 && !pct;
        double[] candidates = year ? new double[]{v - 10, v + 10, v - 1, v + 1, v - 100}
                : pct && integer ? new double[]{v + 1, v - 1, v - 10, v + 10, Math.round(v / 2.0), 100 - v}
                : integer ? new double[]{v + 1, v - 1, v * 2, Math.max(1, Math.round(v / 2.0)), v + 10}
                : new double[]{v * 2, v / 2, v + 0.5, v - 0.5, v * 10};
        var out = new ArrayList<String>();
        for (double c : candidates) {
            if (c <= 0 || c == v || pct && c > 100) continue;
            String s = integer ? String.valueOf((long) c) : trim(c);
            if (decimalComma) s = s.replace('.', ',');
            out.add(pct ? s + "%" : s);
        }
        return out.stream().distinct().toList();
    }
    private static String trim(double d) { String s = String.format(Locale.ROOT, "%.2f", d); return s.replaceAll("0+$", "").replaceAll("\\.$", ""); }

    // ── Question templates ────────────────────────────────────────────────────────

    private static String blanked(Fact f) { return f.sentence().substring(0, f.start()) + "_____" + f.sentence().substring(f.end()); }
    private static String explain(Fact f, boolean vi) { return (vi ? "Theo tài liệu: «" : "From the material: «") + f.sentence() + "»"; }
    private static String raw(String type, Fact f) {
        return "{\n  \"generator\": \"" + MODEL + "\",\n  \"type\": \"" + type + "\",\n  \"sourceSentence\": " + quote(f.sentence()) + ",\n  \"answer\": " + quote(f.term()) + "\n}";
    }
    private static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String level(DifficultyLevel target, DifficultyLevel base) { return (target == null ? base : target).name(); }

    static Candidate multipleChoice(Fact f, Map<Kind, LinkedHashSet<String>> pools, Random rnd, boolean vi, Prompt p) {
        var wrong = distractors(f, pools, 3, rnd);
        if (wrong.size() < 3) return null;
        var options = new ArrayList<CandidateOption>();
        options.add(new CandidateOption(f.term(), true));
        wrong.forEach(w -> options.add(new CandidateOption(w, false)));
        Collections.shuffle(options, rnd);
        String content = (vi ? "Chọn đáp án đúng điền vào chỗ trống: «" : "Choose the answer that fills the blank: «") + blanked(f) + "»";
        return new Candidate("MULTIPLE_CHOICE", content, options, null, explain(f, vi), level(p.difficulty(), DifficultyLevel.MEDIUM), raw("MULTIPLE_CHOICE", f));
    }

    static Candidate trueFalse(Fact f, Map<Kind, LinkedHashSet<String>> pools, Random rnd, boolean vi, Prompt p) {
        boolean makeFalse = rnd.nextBoolean();
        String statement = f.sentence();
        if (makeFalse) {
            var wrong = distractors(f, pools, 1, rnd);
            if (wrong.isEmpty()) makeFalse = false;
            else statement = f.sentence().substring(0, f.start()) + wrong.getFirst() + f.sentence().substring(f.end());
        }
        String yes = vi ? "Đúng" : "True", no = vi ? "Sai" : "False";
        var options = List.of(new CandidateOption(yes, !makeFalse), new CandidateOption(no, makeFalse));
        String content = (vi ? "Nhận định sau đúng hay sai? «" : "True or false? «") + statement + "»";
        String why = makeFalse ? (vi ? "Sai. " : "False. ") + explain(f, vi) : (vi ? "Đúng. " : "True. ") + explain(f, vi);
        DifficultyLevel target = p.difficulty() == DifficultyLevel.HARD ? DifficultyLevel.MEDIUM : DifficultyLevel.EASY;
        return new Candidate("TRUE_FALSE", content, options, null, why, target.name(), raw("TRUE_FALSE", f));
    }

    /**
     * Several statements from different sentences of the material, some made false by swapping the key term:
     * judged one by one (TRUE_FALSE_GROUP, THPT 2025 style) or "choose all true statements" (MULTIPLE_RESPONSE).
     */
    static Candidate statements(QuestionType type, Fact first, List<Fact> facts, Set<String> used, Map<Kind, LinkedHashSet<String>> pools, Random rnd, boolean vi, Prompt p) {
        var picked = new ArrayList<Fact>(List.of(first));
        for (Fact f : facts) {
            if (picked.size() >= 4) break;
            if (!used.contains(f.sentence()) && picked.stream().noneMatch(x -> x.sentence().equals(f.sentence()))) picked.add(f);
        }
        if (picked.size() < 3) return null;
        var options = new ArrayList<CandidateOption>();
        var why = new StringBuilder();
        char label = 'a';
        for (int i = 0; i < picked.size(); i++) {
            Fact f = picked.get(i);
            boolean makeFalse = i > 0 && rnd.nextBoolean();
            String statement = f.sentence();
            if (makeFalse) {
                var wrong = distractors(f, pools, 1, rnd);
                if (wrong.isEmpty()) makeFalse = false;
                else statement = f.sentence().substring(0, f.start()) + wrong.getFirst() + f.sentence().substring(f.end());
            }
            options.add(new CandidateOption(statement, !makeFalse));
            why.append(label++).append(") ").append(makeFalse ? (vi ? "Sai" : "False") : (vi ? "Đúng" : "True")).append(". ");
            used.add(f.sentence());
        }
        String content = type == QuestionType.TRUE_FALSE_GROUP
                ? (vi ? "Mỗi nhận định sau về «" + p.topic() + "» đúng hay sai?" : "Is each statement about «" + p.topic() + "» true or false?")
                : (vi ? "Chọn tất cả các nhận định đúng về «" + p.topic() + "»." : "Choose all the true statements about «" + p.topic() + "».");
        return new Candidate(type.name(), content, options, null, why.toString().strip(), level(p.difficulty(), DifficultyLevel.MEDIUM), raw(type.name(), first));
    }

    /** Up to three facts from different sentences, chained into one passage with a gap per key term (FILL_BLANK). */
    static Candidate cloze(Fact first, List<Fact> facts, Set<String> used, boolean vi, Prompt p) {
        var picked = others(first, facts, used, 3);
        var text = new StringBuilder();
        var options = new ArrayList<CandidateOption>();
        for (Fact f : picked) {
            text.append(text.isEmpty() ? "" : " ").append(blanked(f));
            options.add(new CandidateOption(f.term(), true));
            used.add(f.sentence());
        }
        String content = (vi ? "Điền vào chỗ trống: «" : "Fill in the blanks: «") + text + "»";
        String why = picked.stream().map(f -> explain(f, vi)).reduce((a, b) -> a + " " + b).orElse("");
        return new Candidate("FILL_BLANK", content, options, null, why, level(p.difficulty(), DifficultyLevel.MEDIUM), raw("FILL_BLANK", first));
    }

    /** Sentences with their key term removed, matched to the terms (MATCHING); needs 3+ facts with distinct terms. */
    static Candidate matching(Fact first, List<Fact> facts, Set<String> used, Random rnd, boolean vi, Prompt p) {
        var picked = others(first, facts, used, 4);
        if (picked.size() < 3) return null;
        var options = new ArrayList<CandidateOption>();
        for (Fact f : picked) { options.add(new CandidateOption(blanked(f) + " => " + f.term(), true)); used.add(f.sentence()); }
        String content = vi ? "Nối mỗi câu ở cột trái với từ/cụm từ còn thiếu ở cột phải (chủ đề: " + p.topic() + ")."
                : "Match each sentence on the left with its missing word or phrase on the right (topic: " + p.topic() + ").";
        String why = picked.stream().map(f -> explain(f, vi)).reduce((a, b) -> a + " " + b).orElse("");
        return new Candidate("MATCHING", content, options, null, why, level(p.difficulty(), DifficultyLevel.MEDIUM), raw("MATCHING", first));
    }

    /** The first fact plus unused facts from other sentences whose terms all differ (folded), up to max. */
    private static List<Fact> others(Fact first, List<Fact> facts, Set<String> used, int max) {
        var picked = new ArrayList<Fact>(List.of(first));
        var terms = new HashSet<String>(Set.of(fold(first.term())));
        for (Fact f : facts) {
            if (picked.size() >= max) break;
            if (used.contains(f.sentence()) || f.sentence().equals(first.sentence()) || f.sentence().contains("=>") || !terms.add(fold(f.term()))) continue;
            if (picked.stream().anyMatch(x -> x.sentence().equals(f.sentence()))) continue;
            picked.add(f);
        }
        return picked;
    }

    static Candidate fillIn(Fact f, boolean vi, Prompt p) {
        String content = (vi ? "Điền vào chỗ trống: «" : "Fill in the blank: «") + blanked(f) + "»";
        return new Candidate("SHORT_ANSWER", content, List.of(), f.term(), explain(f, vi), level(p.difficulty(), DifficultyLevel.MEDIUM), raw("SHORT_ANSWER", f));
    }

    static Candidate essay(Fact f, boolean vi, Prompt p) {
        String focus = f.kind() == Kind.KEYWORD ? f.term() : p.topic();
        String content = vi ? "Trình bày ngắn gọn hiểu biết của em về «" + focus + "» (chủ đề: " + p.topic() + ")."
                : "Briefly explain «" + focus + "» (topic: " + p.topic() + ").";
        String guide = (vi ? "Gợi ý chấm: bài làm nêu được ý «" : "Marking guide: the answer states «") + f.sentence() + "»";
        return new Candidate("ESSAY", content, List.of(), guide, explain(f, vi), DifficultyLevel.HARD.name(), raw("ESSAY", f));
    }

    /** For tests and diagnostics: the facts the generator would ask about. */
    static List<String> factTerms(String material) {
        var kw = keywords(material);
        return sentences(material).stream().map(s -> fact(s, kw)).filter(Objects::nonNull).map(Fact::term).collect(Collectors.toList());
    }
}
