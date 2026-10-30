package com.datn.analytics.insight;

import com.datn.analytics.query.AnalyticsDto.*;
import com.datn.analytics.query.ExamAnalyticsQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reads an exam's statistics like an assessment specialist would: overall level, questions that were too hard, too
 * easy, often skipped or that do not separate strong and weak students (possible wrong key), and what to do next.
 * Rules always run; with ai=true a language model rewrites the same facts more naturally (cached briefly).
 */
@Service
@RequiredArgsConstructor
public class ExamInsightsService {
    static final int MIN_ATTEMPTS = 5;
    private final ExamAnalyticsQueries queries;
    private final AiWriter ai;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    record CacheEntry(ExamInsights value, Instant at) {}

    public ExamInsights insights(UUID examId, boolean useAi, String lang) {
        boolean en = "en".equalsIgnoreCase(lang);
        var summary = queries.summary(examId);
        var questions = queries.questions(examId);
        var rules = rules(examId, summary, questions, en, ai.available());
        if (!useAi || !ai.available() || summary.submissions() == 0) return rules;
        String key = examId + ":" + (en ? "en" : "vi") + ":" + Objects.hash(summary, questions);
        var hit = cache.get(key);
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofMinutes(30)))) return hit.value();
        var written = ai.write(instructions(en), facts(summary, questions, rules));
        if (written.isEmpty()) return rules;
        var w = written.get();
        var out = new ExamInsights(examId, "AI", w.model(), rules.level(), w.summary(),
                w.strengths().isEmpty() ? rules.strengths() : w.strengths(), w.concerns().isEmpty() ? rules.concerns() : w.concerns(),
                w.actions().isEmpty() ? rules.actions() : w.actions(), rules.flags(), true, Instant.now());
        if (cache.size() > 500) cache.clear();
        cache.put(key, new CacheEntry(out, Instant.now()));
        return out;
    }

    static String instructions(boolean en) {
        return """
                You are an experienced assessment specialist helping a %s school teacher read the results of one exam.
                Write in %s, warm and concrete, for a teacher who is busy. Use ONLY the numbers in FACTS; never invent
                numbers, topics, question content or student names. Refer to questions as "%s N" (the position).
                Return JSON only: {"summary": "2-3 sentences", "strengths": [..], "concerns": [..], "actions": [..]}
                with at most 4 short items per list. Actions must be practical next steps (re-teach, review an answer key,
                replace a question, finish grading, remind students).
                """.formatted(en ? "" : "Vietnamese", en ? "English" : "Vietnamese", en ? "Question" : "Câu");
    }

    static Map<String, Object> facts(ExamSummary s, List<QuestionStats> qs, ExamInsights rules) {
        var f = new LinkedHashMap<String, Object>();
        f.put("submissions", s.submissions()); f.put("graded", s.graded()); f.put("waitingForGrading", s.needsReview() + s.pending());
        f.put("eligibleStudents", s.eligibleCount()); f.put("submissionRate", s.submissionRate());
        f.put("averagePercent", s.averagePercent()); f.put("publishedAveragePercent", s.published() == null ? null : s.published().averagePercent());
        f.put("scoreDistributionPercentBuckets", s.scoreDistribution().stream().map(b -> b.fromPercent() + "-" + b.toPercent() + ": " + b.count()).toList());
        f.put("questions", qs.stream().map(q -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("position", q.position()); m.put("type", q.questionType()); m.put("attempts", q.attempts());
            m.put("correctRate", q.correctRate()); m.put("unanswered", q.unanswered()); m.put("discrimination", q.discrimination());
            return m;
        }).toList());
        f.put("ruleFindings", rules.flags().stream().map(x -> x.position() + ":" + x.issue()).toList());
        f.put("overallLevel", rules.level());
        return f;
    }

    static ExamInsights rules(UUID examId, ExamSummary s, List<QuestionStats> qs, boolean en, boolean aiAvailable) {
        var strengths = new ArrayList<String>(); var concerns = new ArrayList<String>(); var actions = new ArrayList<String>();
        var flags = new ArrayList<QuestionFlag>();
        String Q = en ? "Q" : "Câu ";
        BigDecimal avg = s.published() != null && s.published().averagePercent() != null ? s.published().averagePercent() : s.averagePercent();
        String level = avg == null ? "UNKNOWN" : avg.doubleValue() >= 80 ? "GOOD" : avg.doubleValue() >= 65 ? "FAIR" : avg.doubleValue() >= 50 ? "AVERAGE" : "WEAK";
        if (s.submissions() == 0) {
            return new ExamInsights(examId, "RULES", null, "UNKNOWN",
                    en ? "No submissions yet. The reading appears once students submit." : "Chưa có bài nộp. Nhận xét sẽ có khi thí sinh nộp bài.",
                    List.of(), List.of(), List.of(), List.of(), aiAvailable, Instant.now());
        }
        String pct = avg == null ? "–" : avg.stripTrailingZeros().toPlainString() + "%";
        String summary = en
                ? "%d submissions, %d graded; average %s of the maximum score (%s).".formatted(s.submissions(), s.graded(), pct, levelText(level, true))
                : "%d bài nộp, %d bài đã chấm; điểm trung bình đạt %s điểm tối đa (%s).".formatted(s.submissions(), s.graded(), pct, levelText(level, false));
        if (s.needsReview() + s.pending() > 0) {
            long n = s.needsReview() + s.pending();
            summary += en ? " %d still wait for grading, so figures may change.".formatted(n) : " Còn %d bài chờ chấm nên số liệu có thể thay đổi.".formatted(n);
            actions.add(en ? "Finish grading the %d waiting submissions, then publish results.".formatted(n) : "Chấm nốt %d bài đang chờ rồi công bố kết quả.".formatted(n));
        }
        if (s.submissionRate() != null && s.submissionRate().doubleValue() < 0.8 && s.eligibleCount() != null) {
            int missing = (int) Math.max(0, Math.round(s.eligibleCount() * (1 - s.submissionRate().doubleValue())));
            concerns.add(en ? "Only %s%% of eligible students submitted (about %d missing).".formatted(round(s.submissionRate().doubleValue() * 100), missing)
                    : "Mới %s%% thí sinh được giao đã nộp bài (thiếu khoảng %d em).".formatted(round(s.submissionRate().doubleValue() * 100), missing));
            actions.add(en ? "Remind students who have not submitted, or grant an extra attempt where justified."
                    : "Nhắc các em chưa nộp bài, hoặc cấp thêm lượt nếu có lý do chính đáng.");
        }
        long total = s.scoreDistribution().stream().mapToLong(ScoreBucket::count).sum();
        if (total > 0) {
            long below50 = s.scoreDistribution().stream().filter(b -> b.toPercent() <= 50).mapToLong(ScoreBucket::count).sum();
            long above80 = s.scoreDistribution().stream().filter(b -> b.fromPercent() >= 80).mapToLong(ScoreBucket::count).sum();
            if (above80 * 2 >= total) strengths.add(en ? "%s of graded attempts scored 80%% or more.".formatted(share(above80, total)) : "%s bài đã chấm đạt từ 80%% trở lên.".formatted(share(above80, total)));
            if (below50 * 3 >= total) concerns.add(en ? "%s of graded attempts scored below 50%%.".formatted(share(below50, total)) : "%s bài đã chấm dưới 50%% điểm.".formatted(share(below50, total)));
        }
        var easy = new ArrayList<Integer>(); var hard = new ArrayList<Integer>(); var skipped = new ArrayList<Integer>();
        var negative = new ArrayList<Integer>(); var weak = new ArrayList<Integer>();
        for (var q : qs) {
            long judged = q.attempts() - q.pendingReview();
            if (judged < MIN_ATTEMPTS || q.correctRate() == null) continue;
            double rate = q.correctRate().doubleValue();
            double d = q.discrimination() == null ? Double.NaN : q.discrimination().doubleValue();
            if (rate >= 0.85) easy.add(q.position());
            if (rate < 0.3) { hard.add(q.position()); flags.add(new QuestionFlag(q.position(), q.questionType(), "TOO_HARD", round(rate * 100) + "%")); }
            if (rate >= 0.95 && !Double.isNaN(d) && d < 0.1) flags.add(new QuestionFlag(q.position(), q.questionType(), "TOO_EASY", round(rate * 100) + "%"));
            if (q.attempts() > 0 && q.unanswered() * 5 >= q.attempts()) { skipped.add(q.position()); flags.add(new QuestionFlag(q.position(), q.questionType(), "OFTEN_SKIPPED", share(q.unanswered(), q.attempts()))); }
            if (!Double.isNaN(d) && d < 0) { negative.add(q.position()); flags.add(new QuestionFlag(q.position(), q.questionType(), "NEGATIVE_DISCRIMINATION", q.discrimination().toPlainString())); }
            else if (!Double.isNaN(d) && d < 0.2 && rate < 0.95) { weak.add(q.position()); flags.add(new QuestionFlag(q.position(), q.questionType(), "LOW_DISCRIMINATION", q.discrimination().toPlainString())); }
        }
        if (!easy.isEmpty()) strengths.add(en ? "Most students answered %s correctly (85%%+).".formatted(list(Q, easy)) : "Đa số học sinh làm đúng %s (từ 85%%).".formatted(list(Q, easy)));
        if (!hard.isEmpty()) {
            concerns.add(en ? "%s: fewer than 30%% answered correctly.".formatted(list(Q, hard)) : "%s: dưới 30%% học sinh làm đúng.".formatted(list(Q, hard)));
            actions.add(en ? "Re-teach the knowledge behind %s and give a short practice set.".formatted(list(Q, hard)) : "Dạy lại phần kiến thức của %s và cho luyện thêm vài bài tương tự.".formatted(list(Q, hard)));
        }
        if (!negative.isEmpty()) {
            concerns.add(en ? "%s: strong students did worse than weak ones — the key or wording may be wrong.".formatted(list(Q, negative))
                    : "%s: học sinh giỏi làm sai nhiều hơn học sinh yếu — có thể sai đáp án hoặc đề gây hiểu nhầm.".formatted(list(Q, negative)));
            actions.add(en ? "Check the answer key and wording of %s; regrade if the key was wrong.".formatted(list(Q, negative))
                    : "Kiểm tra lại đáp án và cách diễn đạt %s; nếu sai đáp án thì chấm lại.".formatted(list(Q, negative)));
        }
        if (!weak.isEmpty()) actions.add(en ? "%s barely separate strong and weak students; consider revising them before reuse.".formatted(list(Q, weak))
                : "%s phân loại học sinh kém; nên chỉnh sửa trước khi dùng lại.".formatted(list(Q, weak)));
        if (!skipped.isEmpty()) concerns.add(en ? "%s were often left blank (20%%+) — too long or unclear?".formatted(list(Q, skipped))
                : "%s bị bỏ trống nhiều (từ 20%%) — có thể quá dài hoặc khó hiểu.".formatted(list(Q, skipped)));
        if (qs.stream().allMatch(q -> q.discrimination() == null) && s.graded() > 0 && s.graded() < ExamAnalyticsQueries.MIN_FOR_DISCRIMINATION)
            actions.add(en ? "Question quality (discrimination) appears once at least 10 attempts are graded."
                    : "Chất lượng câu hỏi (độ phân biệt) sẽ có khi đủ 10 bài đã chấm.");
        if (strengths.isEmpty() && concerns.isEmpty()) strengths.add(en ? "Results are balanced; no question stands out." : "Kết quả cân đối, không có câu nào bất thường.");
        flags.sort(Comparator.comparingInt(QuestionFlag::position));
        return new ExamInsights(examId, "RULES", null, level, summary, strengths, concerns, actions, flags, aiAvailable, Instant.now());
    }

    static String levelText(String level, boolean en) {
        return switch (level) {
            case "GOOD" -> en ? "good" : "tốt"; case "FAIR" -> en ? "fairly good" : "khá";
            case "AVERAGE" -> en ? "average" : "trung bình"; case "WEAK" -> en ? "weak" : "yếu"; default -> en ? "not enough data" : "chưa đủ dữ liệu";
        };
    }
    static String round(double v) { return BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    static String share(long part, long total) { return round(part * 100.0 / total) + "%"; }
    static String list(String prefix, List<Integer> positions) {
        var shown = positions.stream().limit(6).map(p -> prefix + p).collect(Collectors.joining(", "));
        return positions.size() > 6 ? shown + "…" : shown;
    }
}
