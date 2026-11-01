package com.datn.questionbank.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Retrieval step of generation (RAG): splits the knowledge text into passages, keeps the ones most related to the
 * topic within a size budget, and labels them by source. Without teacher material it can fetch encyclopedic pages.
 */
@Component
public class KnowledgeRetriever {
    static final int PASSAGE_CHARS = 700, BUDGET_CHARS = 9000;
    private final WikipediaClient wikipedia;
    private final boolean webEnabled;

    public KnowledgeRetriever(WikipediaClient wikipedia, @Value("${question-bank.ai.web-knowledge:true}") boolean webEnabled) {
        this.wikipedia = wikipedia;
        this.webEnabled = webEnabled;
    }

    public boolean webEnabled() { return webEnabled; }

    public record Source(String title, String url) {}
    /** @param text passages joined as "[n] Source: …", ready for the prompt; null when there is no knowledge */
    public record Knowledge(String text, List<Source> sources, List<String> warnings) {}
    record Passage(String source, String text, int order) {}

    public Knowledge retrieve(String topic, String material, String language, boolean useWeb) {
        var warnings = new ArrayList<String>();
        var sources = new ArrayList<Source>();
        var passages = new ArrayList<Passage>();
        if (material != null && !material.isBlank()) {
            passages.addAll(split("Tài liệu của giáo viên", material, 0));
            sources.add(new Source("Tài liệu của giáo viên", null));
        } else if (useWeb && webEnabled) {
            var found = wikipedia.search(topic, language);
            if (found.warning() != null) warnings.add(found.warning());
            for (var page : found.pages()) {
                passages.addAll(split("Wikipedia – " + page.title(), page.text(), passages.size()));
                sources.add(new Source("Wikipedia – " + page.title(), page.url()));
            }
        }
        if (passages.isEmpty()) return new Knowledge(null, List.of(), warnings);
        var chosen = select(topic, passages);
        var sb = new StringBuilder();
        int n = 1;
        for (var p : chosen) sb.append('[').append(n++).append("] ").append(p.source()).append(": ").append(p.text()).append("\n\n");
        return new Knowledge(sb.toString().strip(), sources, warnings);
    }

    /** Paragraph-aware chunks of about PASSAGE_CHARS, never cutting a sentence when avoidable. */
    static List<Passage> split(String source, String text, int orderBase) {
        var out = new ArrayList<Passage>();
        var buf = new StringBuilder();
        for (String sentence : text.replace("\r", "").split("(?<=[.!?…])\\s+|\\n{2,}")) {
            String s = sentence.strip();
            if (s.isEmpty() || s.matches("=+.*=+")) continue;
            if (buf.length() > 0 && buf.length() + s.length() + 1 > PASSAGE_CHARS) { out.add(new Passage(source, buf.toString(), orderBase + out.size())); buf.setLength(0); }
            if (buf.length() > 0) buf.append(' ');
            buf.append(s.length() > PASSAGE_CHARS * 2 ? s.substring(0, PASSAGE_CHARS * 2) : s);
        }
        if (buf.length() > 0) out.add(new Passage(source, buf.toString(), orderBase + out.size()));
        return out;
    }

    /** Topic-overlap ranking with rare-term weighting; the chosen passages are returned in their original order. */
    static List<Passage> select(String topic, List<Passage> passages) {
        int total = passages.stream().mapToInt(p -> p.text().length()).sum();
        if (total <= BUDGET_CHARS) return passages;
        var topicTokens = LocalQuestionGenerator.tokens(topic);
        var df = new HashMap<String, Integer>();
        var tokens = new ArrayList<Set<String>>();
        for (var p : passages) { var t = LocalQuestionGenerator.tokens(p.text()); tokens.add(t); t.forEach(w -> df.merge(w, 1, Integer::sum)); }
        var scored = new ArrayList<Map.Entry<Passage, Double>>();
        for (int i = 0; i < passages.size(); i++) {
            double score = 0;
            for (String w : topicTokens) if (tokens.get(i).contains(w)) score += Math.log(1 + (double) passages.size() / df.get(w));
            score += 0.01 / (1 + passages.get(i).order()); // ties: earlier passages (introductions) first
            scored.add(Map.entry(passages.get(i), score));
        }
        scored.sort(Map.Entry.<Passage, Double>comparingByValue().reversed());
        var chosen = new ArrayList<Passage>();
        int used = 0;
        for (var e : scored) { if (used + e.getKey().text().length() > BUDGET_CHARS) continue; chosen.add(e.getKey()); used += e.getKey().text().length(); }
        chosen.sort(Comparator.comparingInt(Passage::order));
        return chosen;
    }
}
