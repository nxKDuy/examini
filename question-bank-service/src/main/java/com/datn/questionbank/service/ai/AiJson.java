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

    /** Parses {"questions":[...]} (or a bare array, optionally inside a ``` fence) into candidates. */
    static List<Candidate> questions(String text) {
        JsonNode payload;
        try { payload = JSON.readTree(stripFence(text == null ? "" : text)); }
        catch (Exception e) { throw new GenerationException("AI trả về sai định dạng", e); }
        JsonNode list = payload == null ? null : payload.isArray() ? payload : payload.path("questions");
        if (list == null || !list.isArray()) throw new GenerationException("AI trả về sai định dạng");
        var out = new ArrayList<Candidate>();
        for (JsonNode q : list) {
            if (!q.isObject()) continue;
            var options = new ArrayList<CandidateOption>();
            for (JsonNode o : q.path("options"))
                options.add(new CandidateOption(text(o.path("content")), o.path("correct").isBoolean() ? o.path("correct").asBoolean() : null));
            String raw;
            try { raw = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(q); } catch (Exception e) { raw = q.toString(); }
            out.add(new Candidate(text(q.path("type")), text(q.path("content")), options, text(q.path("expectedAnswer")),
                    text(q.path("explanation")), text(q.path("difficulty")), raw));
        }
        return out;
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
