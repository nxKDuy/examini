package com.datn.questionbank.service.ai;

import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.time.Duration;
import java.util.*;

/** Google Gemini (same provider family as grading-service essay AI). Rate limits are reported as transient. */
@Component
public class GeminiQuestionGenerator implements AiProvider {
    static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private final String apiKey;
    private final String modelName;
    private final String endpoint;
    private final RestTemplate http;

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiQuestionGenerator(@Value("${question-bank.ai.gemini-api-key:}") String apiKey,
                                   @Value("${question-bank.ai.gemini-model:gemini-2.5-flash}") String modelName,
                                   @Value("${question-bank.ai.timeout-seconds:120}") int timeoutSeconds) {
        this(apiKey, modelName, timeoutSeconds, ENDPOINT);
    }
    GeminiQuestionGenerator(String apiKey, String modelName, int timeoutSeconds, String endpoint) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.modelName = modelName;
        this.endpoint = endpoint;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(10, timeoutSeconds) * 1000);
        this.http = new RestTemplate(factory);
    }

    @Override public String name() { return "gemini"; }
    @Override public boolean available() { return !apiKey.isEmpty() && !"demo-key".equals(apiKey); }
    @Override public String model() { return "gemini/" + modelName; }

    @Override
    public Result generate(Prompt prompt) {
        if (!available()) throw new GenerationException("Chưa cấu hình Gemini (GEMINI_API_KEY)");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);
        var body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", AiPrompts.system(prompt)))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", AiPrompts.user(prompt))))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", AiJson.geminiSchema(), "temperature", 0.7));
        String raw;
        try {
            raw = http.postForObject(endpoint.formatted(modelName), new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            throw failure(e.getStatusCode().value(), e.getResponseHeaders(), e);
        } catch (RestClientException e) {
            throw new GenerationException("Gemini không phản hồi", true, null, e);
        }
        return parse(raw).withModel(model());
    }

    /** 429 (quota / RESOURCE_EXHAUSTED) and 5xx are transient; other 4xx (bad key, bad model) are not. */
    static GenerationException failure(int status, HttpHeaders headers, Throwable cause) {
        Duration after = null;
        String h = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (h != null) try { after = Duration.ofSeconds(Long.parseLong(h.strip())); } catch (NumberFormatException ignored) {}
        if (status == 429) return new GenerationException("Gemini đang bị giới hạn lượt gọi", true, after, cause);
        if (status >= 500) return new GenerationException("Gemini tạm thời lỗi (" + status + ")", true, after, cause);
        if (status == 401 || status == 403) return new GenerationException("Khóa Gemini không hợp lệ hoặc không có quyền", false, null, cause);
        return new GenerationException("Gemini từ chối yêu cầu (" + status + ")", false, null, cause);
    }

    /** Parses a generateContent response; tolerant of extra fields, strict about the shape it needs. */
    static Result parse(String raw) {
        JsonNode root;
        try { root = AiJson.JSON.readTree(raw == null ? "" : raw); }
        catch (Exception e) { throw new GenerationException("AI trả về sai định dạng", e); }
        JsonNode candidate = root.path("candidates").path(0);
        if (candidate.isMissingNode())
            throw new GenerationException(root.path("promptFeedback").path("blockReason").isMissingNode()
                    ? "AI không trả về kết quả" : "AI từ chối yêu cầu (nội dung bị chặn)");
        var text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) text.append(part.path("text").asText(""));
        var warnings = new ArrayList<String>();
        if ("MAX_TOKENS".equals(candidate.path("finishReason").asText(""))) warnings.add("Kết quả AI bị cắt do quá dài; một số câu có thể bị thiếu");
        return new Result(AiJson.questions(text.toString()), warnings);
    }

    static Map<String, Object> schema() { return AiJson.geminiSchema(); }
}
