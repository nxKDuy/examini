package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Shared structured-output schema and parser for LLM providers. */
final class AiJson {
    private AiJson() {}
    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Parses {"questions":[...]} (or a bare array, optionally inside a ``` fence) into candidates. Models without a
     * response schema often rename fields, so common variants are accepted: question/stem/text for the content,
     * options as plain strings or {text|label}, and the key as a per-option flag or an "answer" (letter, index or
     * the option's text). The key is only ever taken from what the model wrote; an unmatched answer stays unmarked.
     */
    static List<Candidate> questions(String text) {
        JsonNode payload;
        try { payload = JSON.readTree(stripFence(text == null ? "" : text)); }
        catch (Exception e) { throw new GenerationException("AI trả về sai định dạng", e); }
        JsonNode list = payload == null ? null : payload.isArray() ? payload : first(payload, "questions", "items", "quiz", "data");
        if (list == null || !list.isArray()) throw new GenerationException("AI trả về sai định dạng");
        var out = new ArrayList<Candidate>();
        for (JsonNode q : list) {
            if (!q.isObject()) continue;
            JsonNode opts = first(q, "options", "choices", "answers", "statements");
            String answer = text(first(q, "answer", "correctAnswer", "correct_answer", "correctOption", "key"));
            JsonNode answers = first(q, "correctAnswers", "correct_answers", "correctOptions");
            var options = new ArrayList<CandidateOption>();
            if (opts != null && opts.isArray()) {
                int i = 0;
                for (JsonNode o : opts) {
                    String label = o.isObject() ? text(first(o, "label", "letter", "key")) : null;
                    String content = o.isObject() ? text(first(o, "content", "text", "option", "value", "statement")) : o.asText(null);
                    if (content != null) content = content.replaceFirst("^\\s*[A-Fa-f][.)]\\s+", "");
                    JsonNode flag = o.isObject() ? first(o, "correct", "isCorrect", "is_correct", "true", "truth") : null;
                    Boolean correct;
                    if (flag != null && flag.isBoolean()) correct = flag.asBoolean();
                    else if (keyed(answer, answers, i, label, content)) correct = Boolean.TRUE;
                    else correct = answer != null || answers != null ? Boolean.FALSE : null;
                    options.add(new CandidateOption(content, correct));
                    i++;
                }
            }
            String expected = text(first(q, "expectedAnswer", "expected_answer", "markingGuide", "rubric"));
            if (expected == null && options.isEmpty()) expected = answer;
            String raw;
            try { raw = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(q); } catch (Exception e) { raw = q.toString(); }
            out.add(new Candidate(text(first(q, "type", "questionType", "question_type")), text(first(q, "content", "question", "stem", "text", "prompt")),
                    options, expected, text(first(q, "explanation", "rationale", "solution")), text(first(q, "difficulty", "level")), raw));
        }
        return out;
    }

    /** True when the model's own key points at option {@code i}: by letter, 1-based number, label or exact text. */
    static boolean keyed(String answer, JsonNode answers, int i, String label, String content) {
        var keys = new ArrayList<String>();
        if (answer != null) keys.add(answer);
        if (answers != null && answers.isArray()) answers.forEach(a -> keys.add(a.asText()));
        String letter = String.valueOf((char) ('A' + i));
        for (String k : keys) {
            String t = k.strip();
            if (t.equalsIgnoreCase(letter) || t.matches("(?i)" + letter + "[.)]?") || t.equals(String.valueOf(i + 1))) return true;
            if (label != null && t.equalsIgnoreCase(label.strip())) return true;
            if (content != null && t.equalsIgnoreCase(content.strip())) return true;
        }
        return false;
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String n : names) { JsonNode v = node.get(n); if (v != null && !v.isNull()) return v; }
        return null;
    }

    private static String text(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(); }
    static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) { t = t.replaceFirst("^```[a-zA-Z]*\\s*", ""); if (t.endsWith("```")) t = t.substring(0, t.length() - 3); }
        return t.strip();
    }

    /** Gemini-style schema (OpenAPI subset, upper-case types). */
    static Map<String, Object> geminiSchema() { return schema(true); }
    /** JSON Schema for OpenAI-compatible / Ollama structured outputs (lower-case types). */
    static Map<String, Object> jsonSchema() { return schema(false); }

    private static Map<String, Object> schema(boolean gemini) {
        String obj = gemini ? "OBJECT" : "object", arr = gemini ? "ARRAY" : "array", str = gemini ? "STRING" : "string", bool = gemini ? "BOOLEAN" : "boolean";
        var types = Arrays.stream(QuestionType.values()).map(Enum::name).toList();
        Map<String, Object> option = Map.of("type", obj,
                "properties", Map.of("content", Map.of("type", str), "correct", Map.of("type", bool)),
                "required", List.of("content", "correct"));
        Map<String, Object> question = Map.of("type", obj,
                "properties", Map.of(
                        "type", Map.of("type", str, "enum", types),
                        "content", Map.of("type", str),
                        "options", Map.of("type", arr, "items", option),
                        "expectedAnswer", Map.of("type", str),
                        "explanation", Map.of("type", str),
                        "difficulty", Map.of("type", str, "enum", List.of("EASY", "MEDIUM", "HARD"))),
                "required", List.of("type", "content", "explanation", "difficulty"));
        return Map.of("type", obj, "properties", Map.of("questions", Map.of("type", arr, "items", question)),
                "required", List.of("questions"));
    }
}
