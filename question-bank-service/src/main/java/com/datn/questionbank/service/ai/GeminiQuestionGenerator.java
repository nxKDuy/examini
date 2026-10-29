package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.QuestionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.util.*;

/** Google Gemini implementation (same provider family as grading-service essay AI). */
@Component
public class GeminiQuestionGenerator implements AiQuestionGenerator {
    static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String apiKey;
    private final String modelName;
    private final RestTemplate http;

    public GeminiQuestionGenerator(@Value("${question-bank.ai.gemini-api-key:}") String apiKey,
                                   @Value("${question-bank.ai.gemini-model:gemini-2.5-flash}") String modelName,
                                   @Value("${question-bank.ai.timeout-seconds:120}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.modelName = modelName;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(10, timeoutSeconds) * 1000);
        this.http = new RestTemplate(factory);
    }

    @Override public boolean available() { return !apiKey.isEmpty() && !"demo-key".equals(apiKey); }
    @Override public String model() { return "gemini/" + modelName; }

    @Override
    public Result generate(Prompt prompt) {
        if (!available()) throw new GenerationException("Chưa cấu hình AI (GEMINI_API_KEY)");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);
        var body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", AiPrompts.system(prompt)))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", AiPrompts.user(prompt))))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema(), "temperature", 0.7));
        String raw;
        try {
            raw = http.postForObject(ENDPOINT.formatted(modelName), new HttpEntity<>(body, headers), String.class);
        } catch (RestClientException e) {
            throw new GenerationException("Dịch vụ AI không phản hồi; vui lòng thử lại sau", e);
        }
        return parse(raw);
    }

    /** Parses a generateContent response; tolerant of extra fields, strict about the shape it needs. */
    static Result parse(String raw) {
        try {
            JsonNode root = JSON.readTree(raw == null ? "" : raw);
            JsonNode candidate = root.path("candidates").path(0);
            String finish = candidate.path("finishReason").asText("");
            if (candidate.isMissingNode())
                throw new GenerationException(root.path("promptFeedback").path("blockReason").isMissingNode()
                        ? "AI không trả về kết quả" : "AI từ chối yêu cầu (nội dung bị chặn)");
            var text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) text.append(part.path("text").asText(""));
            JsonNode payload = JSON.readTree(stripFence(text.toString()));
            JsonNode list = payload.isArray() ? payload : payload.path("questions");
            if (!list.isArray()) throw new GenerationException("AI trả về sai định dạng");
            var out = new ArrayList<Candidate>();
            for (JsonNode q : list) {
                if (!q.isObject()) continue;
                var options = new ArrayList<CandidateOption>();
                for (JsonNode o : q.path("options"))
                    options.add(new CandidateOption(text(o.path("content")), o.path("correct").isBoolean() ? o.path("correct").asBoolean() : null));
                out.add(new Candidate(text(q.path("type")), text(q.path("content")), options, text(q.path("expectedAnswer")),
                        text(q.path("explanation")), text(q.path("difficulty")), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(q)));
            }
            var warnings = new ArrayList<String>();
            if ("MAX_TOKENS".equals(finish)) warnings.add("Kết quả AI bị cắt do quá dài; một số câu có thể bị thiếu");
            return new Result(out, warnings);
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException("AI trả về sai định dạng", e);
        }
    }

    private static String text(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(); }
    private static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) { t = t.replaceFirst("^```[a-zA-Z]*\\s*", ""); if (t.endsWith("```")) t = t.substring(0, t.length() - 3); }
        return t.strip();
    }

    /** Gemini structured-output schema (OpenAPI subset). */
    static Map<String, Object> schema() {
        var types = Arrays.stream(QuestionType.values()).map(Enum::name).toList();
        Map<String, Object> option = Map.of("type", "OBJECT",
                "properties", Map.of("content", Map.of("type", "STRING"), "correct", Map.of("type", "BOOLEAN")),
                "required", List.of("content", "correct"));
        Map<String, Object> question = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "type", Map.of("type", "STRING", "enum", types),
                        "content", Map.of("type", "STRING"),
                        "options", Map.of("type", "ARRAY", "items", option),
                        "expectedAnswer", Map.of("type", "STRING"),
                        "explanation", Map.of("type", "STRING"),
                        "difficulty", Map.of("type", "STRING", "enum", List.of("EASY", "MEDIUM", "HARD"))),
                "required", List.of("type", "content", "explanation", "difficulty"));
        return Map.of("type", "OBJECT", "properties", Map.of("questions", Map.of("type", "ARRAY", "items", question)),
                "required", List.of("questions"));
    }
}
