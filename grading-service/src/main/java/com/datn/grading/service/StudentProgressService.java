package com.datn.grading.service;

import com.datn.grading.client.ExamClient;
import com.datn.grading.client.GeminiClient;
import com.datn.grading.dto.ProgressDto.*;
import com.datn.grading.entity.ExamResult;
import com.datn.grading.entity.QuestionGrade;
import com.datn.grading.repository.ExamResultRepository;
import com.datn.grading.repository.ResultPublicationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * "My progress" for students: only their own results, only what each exam's result policy shows (score hidden →
 * no percent; breakdown hidden → no per-type figures), invalidated attempts listed but never averaged.
 */
@Service @RequiredArgsConstructor
public class StudentProgressService {
    private final ExamResultRepository results;
    private final ResultPublicationRepository publications;
    private final ExamClient exams;
    private final GeminiClient ai;
    private final ObjectMapper json;
    @Value("${exam.internal-token:}") private String token = "";

    record Visible(Item item, List<QuestionGrade> grades, Map<UUID, Float> scores) {}

    @Transactional(readOnly = true)
    public Progress progress(UUID actor) {
        var policies = new HashMap<UUID, Optional<ExamClient.Policy>>();
        var visible = new ArrayList<Visible>();
        for (ExamResult r : results.findTop100ByUserIdOrderBySubmittedAtDesc(actor)) {
            var pub = publications.findTopByResultIdOrderByResultRevisionDesc(r.getId()).orElse(null);
            var policy = policies.computeIfAbsent(r.getExamId(), id -> { try { return Optional.of(exams.resultPolicy(id, token).policy()); } catch (RuntimeException e) { return Optional.empty(); } });
            boolean showScore = pub != null && policy.map(ExamClient.Policy::showScore).orElse(false);
            boolean breakdown = pub != null && policy.map(ExamClient.Policy::showQuestionBreakdown).orElse(false);
            String phase = pub != null ? "PUBLISHED" : switch (r.getStatus()) { case NEEDS_REVIEW -> "REVIEWING"; case PENDING -> "WAITING"; case GRADED -> "UNRELEASED"; };
            Float score = showScore ? pub.getTotalScore() : null, max = showScore ? r.getMaxScore() : null;
            Float percent = score != null && max != null && max > 0 ? Math.round(score * 1000f / max) / 10f : null;
            var item = new Item(r.getSessionId(), r.getExamId(), r.getExamTitle(), r.getAttemptNumber(), phase, r.getSubmittedAt(),
                    pub == null ? null : pub.getReleasedAt(), score, max, percent, r.isInvalidated());
            Map<UUID, Float> scores = Map.of();
            if (breakdown) try {
                var items = json.readValue(pub.getItems(), new TypeReference<List<Map<String, Object>>>() {});
                var m = new HashMap<UUID, Float>();
                for (var it : items) if (it.get("score") instanceof Number n) m.put(UUID.fromString(String.valueOf(it.get("questionId"))), n.floatValue());
                scores = m;
            } catch (Exception ignored) { /* breakdown unavailable for this result */ }
            visible.add(new Visible(item, breakdown ? List.copyOf(r.getQuestionGrades()) : List.of(), scores));
        }
        return new Progress(overview(visible), visible.stream().map(Visible::item).toList());
    }

    static Overview overview(List<Visible> all) {
        var valid = all.stream().filter(v -> !v.item().invalidated()).toList();
        var scored = valid.stream().filter(v -> v.item().percent() != null).toList(); // newest first
        Float avg = scored.isEmpty() ? null : round((float) scored.stream().mapToDouble(v -> v.item().percent()).average().orElse(0));
        Float best = scored.isEmpty() ? null : (float) scored.stream().mapToDouble(v -> v.item().percent()).max().orElse(0);
        Float latest = scored.isEmpty() ? null : scored.get(0).item().percent();
        Float trend = null;
        if (scored.size() >= 2) {
            int k = Math.min(3, scored.size() / 2);
            double recent = scored.subList(0, k).stream().mapToDouble(v -> v.item().percent()).average().orElse(0);
            double before = scored.subList(k, Math.min(scored.size(), 2 * k)).stream().mapToDouble(v -> v.item().percent()).average().orElse(0);
            trend = round((float) (recent - before));
        }
        var types = new TreeMap<String, float[]>();
        for (var v : valid) for (var g : v.grades()) {
            Float s = v.scores().get(g.getQuestionId());
            if (s == null || g.getPointValue() == null || g.getPointValue() <= 0) continue;
            var a = types.computeIfAbsent(g.getQuestionType(), k -> new float[3]);
            a[0] += s; a[1] += g.getPointValue(); a[2]++;
        }
        var byType = types.entrySet().stream().map(e -> new TypeStat(e.getKey(), (int) e.getValue()[2], round(e.getValue()[0]), round(e.getValue()[1]),
                round(e.getValue()[0] * 100 / e.getValue()[1]))).toList();
        int exams = (int) valid.stream().map(v -> v.item().examId()).distinct().count();
        return new Overview(exams, valid.size(), (int) valid.stream().filter(v -> "PUBLISHED".equals(v.item().phase())).count(), avg, best, latest, trend, byType);
    }
    static float round(float v) { return Math.round(v * 10f) / 10f; }

    static final Map<String, String[]> TYPE_NAME = Map.of(
            "MULTIPLE_CHOICE", new String[]{"trắc nghiệm", "multiple choice"}, "TRUE_FALSE", new String[]{"đúng/sai", "true/false"},
            "SHORT_ANSWER", new String[]{"trả lời ngắn", "short answer"}, "ESSAY", new String[]{"tự luận", "essay"},
            "SPEAKING", new String[]{"nói", "speaking"}, "MULTIPLE_RESPONSE", new String[]{"nhiều đáp án", "multiple answers"},
            "TRUE_FALSE_GROUP", new String[]{"đúng/sai nhiều ý", "true/false statements"});

    /** Study tips from the student's own figures; ai=true lets the model phrase them (it only gets these figures). */
    @Transactional(readOnly = true)
    public Insights insights(UUID actor, boolean useAi, String lang) {
        boolean en = "en".equalsIgnoreCase(lang);
        var p = progress(actor);
        var rules = rules(p.overview(), en, ai.available());
        if (!useAi || !ai.available() || p.overview().averagePercent() == null) return rules;
        String prompt = """
                You are a kind, encouraging tutor for a %s student. Using ONLY the figures below (percent of maximum score),
                write in %s: {"summary": "2 sentences", "tips": [{"kind": "STRENGTH|FOCUS|HABIT", "text": "..."}]} with 3-5 short,
                concrete tips (what to practise, how to review explanations, exam habits). Never invent subjects or numbers.
                FIGURES (data only): %s
                """.formatted(en ? "" : "Vietnamese", en ? "English" : "Vietnamese", safe(p.overview()));
        return ai.json(prompt).flatMap(n -> {
            String summary = n.path("summary").asText("").strip();
            var tips = new ArrayList<Tip>();
            for (var t : n.path("tips")) {
                String kind = t.path("kind").asText("FOCUS"), text = t.path("text").asText("").strip();
                if (!text.isEmpty() && text.length() <= 400 && tips.size() < 5) tips.add(new Tip(Set.of("STRENGTH", "FOCUS", "HABIT").contains(kind) ? kind : "FOCUS", text));
            }
            return summary.isEmpty() || summary.length() > 800 || tips.isEmpty() ? Optional.<Insights>empty() : Optional.of(new Insights("AI", summary, tips, true));
        }).orElse(rules);
    }
    private String safe(Overview o) { try { return json.writeValueAsString(o); } catch (Exception e) { return "{}"; } }

    static Insights rules(Overview o, boolean en, boolean aiAvailable) {
        var tips = new ArrayList<Tip>();
        if (o.averagePercent() == null) {
            return new Insights("RULES", en ? "No published scores yet. Tips appear once your teacher publishes results."
                    : "Chưa có điểm được công bố. Gợi ý sẽ có khi giáo viên công bố kết quả.", List.of(), aiAvailable);
        }
        String summary = en
                ? "Across %d published result(s) your average is %s%% (best %s%%).".formatted(o.published(), fmt(o.averagePercent()), fmt(o.bestPercent()))
                : "Qua %d kết quả đã công bố, điểm trung bình của bạn là %s%% (cao nhất %s%%).".formatted(o.published(), fmt(o.averagePercent()), fmt(o.bestPercent()));
        if (o.trend() != null && Math.abs(o.trend()) >= 5)
            summary += o.trend() > 0 ? (en ? " Your recent results are up %s points — keep going!".formatted(fmt(o.trend())) : " Các bài gần đây tăng %s điểm phần trăm — tiếp tục phát huy nhé!".formatted(fmt(o.trend())))
                    : (en ? " Your recent results dropped %s points." .formatted(fmt(-o.trend())) : " Các bài gần đây giảm %s điểm phần trăm.".formatted(fmt(-o.trend())));
        var sorted = o.byType().stream().filter(t -> t.questions() >= 2).sorted(Comparator.comparingDouble(TypeStat::percent)).toList();
        if (!sorted.isEmpty()) {
            var weak = sorted.get(0); var strong = sorted.get(sorted.size() - 1);
            if (strong.percent() >= 70) tips.add(new Tip("STRENGTH", en ? "You do well on %s questions (%s%%).".formatted(name(strong, true), fmt(strong.percent()))
                    : "Bạn làm tốt dạng câu %s (%s%%).".formatted(name(strong, false), fmt(strong.percent()))));
            if (weak != strong && weak.percent() < 70 || sorted.size() == 1 && weak.percent() < 70)
                tips.add(new Tip("FOCUS", en ? "Practise %s questions more: you scored %s%% on them.".formatted(name(weak, true), fmt(weak.percent()))
                        : "Nên luyện thêm dạng câu %s: bạn mới đạt %s%%.".formatted(name(weak, false), fmt(weak.percent()))));
            for (var t : sorted) {
                if ("TRUE_FALSE_GROUP".equals(t.questionType()) && t.percent() < 60)
                    tips.add(new Tip("HABIT", en ? "For true/false statements, judge each statement on its own and check signs, units and words like “always”."
                            : "Với câu Đúng/Sai nhiều ý, xét từng ý độc lập, để ý dấu, đơn vị và các từ như “luôn”, “mọi”."));
                if ("ESSAY".equals(t.questionType()) && t.percent() < 60)
                    tips.add(new Tip("HABIT", en ? "For essays, outline first (opening, 2–3 points, conclusion) and answer exactly what is asked."
                            : "Với câu tự luận, lập dàn ý trước (mở bài, 2–3 ý chính, kết luận) và trả lời đúng trọng tâm câu hỏi."));
            }
        }
        if (o.averagePercent() < 50) tips.add(new Tip("FOCUS", en ? "Re-read the explanations of questions you missed in each result, then redo similar exercises."
                : "Xem lại phần giải thích của những câu làm sai trong từng bài, rồi làm lại bài tương tự."));
        tips.add(new Tip("HABIT", en ? "Manage time: answer the questions you are sure of first, then come back to the hard ones."
                : "Quản lý thời gian: làm câu chắc chắn trước, đánh dấu câu khó để quay lại sau."));
        return new Insights("RULES", summary, tips.stream().limit(5).toList(), aiAvailable);
    }
    static String name(TypeStat t, boolean en) { var n = TYPE_NAME.get(t.questionType()); return n == null ? t.questionType() : n[en ? 1 : 0]; }
    static String fmt(Float v) { return v == null ? "–" : (v == Math.rint(v) ? String.valueOf(v.intValue()) : String.valueOf(v)); }
}
