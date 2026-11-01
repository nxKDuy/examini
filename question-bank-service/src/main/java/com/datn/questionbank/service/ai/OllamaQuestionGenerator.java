package com.datn.questionbank.service.ai;

import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.util.*;

/**
 * Self-hosted open model through Ollama (e.g. qwen2.5:7b-instruct): no API quota and no per-call cost, at the price
 * of a server with enough RAM. Uses Ollama structured outputs ({@code format} = JSON Schema).
 */
@Component
public class OllamaQuestionGenerator implements AiProvider {
    private final String baseUrl;
    private final String modelName;
    private final RestTemplate http;

    public OllamaQuestionGenerator(@Value("${question-bank.ai.ollama-url:}") String baseUrl,
                                   @Value("${question-bank.ai.ollama-model:qwen2.5:7b-instruct}") String modelName,
                                   @Value("${question-bank.ai.ollama-timeout-seconds:300}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.modelName = modelName;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(Math.max(10, timeoutSeconds) * 1000);
        this.http = new RestTemplate(factory);
    }

    @Override public String name() { return "ollama"; }
    @Override public boolean available() { return !baseUrl.isEmpty(); }
    @Override public String model() { return "ollama/" + modelName; }

    @Override
    public String completeJson(String system, String user) {
        if (!available()) throw new GenerationException("Chưa cấu hình mô hình tự chạy (OLLAMA_URL)");
        var body = Map.of("model", modelName, "stream", false, "format", "json", "options", Map.of("temperature", 0.0),
                "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String raw = http.postForObject(baseUrl + "/api/chat", new HttpEntity<>(body, headers), String.class);
            return AiJson.JSON.readTree(raw == null ? "" : raw).path("message").path("content").asText("");
        } catch (HttpStatusCodeException e) {
            int s = e.getStatusCode().value();
            throw new GenerationException("Máy chủ AI tự chạy lỗi (" + s + ")", s == 429 || s >= 500, null, e);
        } catch (RestClientException e) {
            throw new GenerationException("Không kết nối được máy chủ AI tự chạy", true, null, e);
        } catch (Exception e) {
            throw new GenerationException("AI trả về sai định dạng", e);
        }
    }

    @Override
    public Result generate(Prompt prompt) {
        if (!available()) throw new GenerationException("Chưa cấu hình mô hình tự chạy (OLLAMA_URL)");
        var body = Map.of("model", modelName, "stream", false, "format", AiJson.jsonSchema(),
                "options", Map.of("temperature", 0.7),
                "messages", List.of(Map.of("role", "system", "content", AiPrompts.system(prompt)),
                        Map.of("role", "user", "content", AiPrompts.user(prompt))));
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String raw;
        try {
            raw = http.postForObject(baseUrl + "/api/chat", new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            int s = e.getStatusCode().value();
            boolean model404 = s == 404;
            throw new GenerationException(model404 ? "Máy chủ AI tự chạy chưa có mô hình " + modelName + " (chạy: ollama pull " + modelName + ")"
                    : "Máy chủ AI tự chạy lỗi (" + s + ")", s == 429 || s >= 500, null, e);
        } catch (RestClientException e) {
            throw new GenerationException("Không kết nối được máy chủ AI tự chạy", true, null, e);
        }
        JsonNode root;
        try { root = AiJson.JSON.readTree(raw == null ? "" : raw); }
        catch (Exception e) { throw new GenerationException("AI trả về sai định dạng", e); }
        var warnings = new ArrayList<String>();
        if ("length".equals(root.path("done_reason").asText(""))) warnings.add("Kết quả AI bị cắt do quá dài; một số câu có thể bị thiếu");
        return new Result(AiJson.questions(root.path("message").path("content").asText("")), warnings, model());
    }
}
