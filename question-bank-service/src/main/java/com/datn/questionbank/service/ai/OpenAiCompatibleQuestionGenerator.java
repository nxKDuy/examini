package com.datn.questionbank.service.ai;

import com.datn.questionbank.service.ai.AiQuestionGenerator.GenerationException;
import com.datn.questionbank.service.ai.AiQuestionGenerator.Prompt;
import com.datn.questionbank.service.ai.AiQuestionGenerator.Result;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Any OpenAI-compatible chat-completions API: free tiers such as Groq ({@code https://api.groq.com/openai/v1}),
 * OpenRouter {@code :free} models ({@code https://openrouter.ai/api/v1}), Mistral, Cerebras, GitHub Models, or a local
 * server (LM Studio, vLLM, llama.cpp, Ollama's {@code /v1}). One adapter, configured by base URL + key + model.
 * Asks for a JSON object; when a server rejects {@code response_format} the call is repeated once without it.
 */
@Component
public class OpenAiCompatibleQuestionGenerator implements AiProvider {
    private final String baseUrl, apiKey, modelName;
    private final RestTemplate http;

    @org.springframework.beans.factory.annotation.Autowired
    public OpenAiCompatibleQuestionGenerator(@Value("${question-bank.ai.openai-url:}") String baseUrl,
                                             @Value("${question-bank.ai.openai-key:}") String apiKey,
                                             @Value("${question-bank.ai.openai-model:}") String modelName,
                                             @Value("${question-bank.ai.timeout-seconds:120}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.modelName = modelName == null ? "" : modelName.strip();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(Math.max(10, timeoutSeconds) * 1000);
        this.http = new RestTemplate(factory);
    }

    @Override public String name() { return "openai"; }
    @Override public boolean available() { return !baseUrl.isEmpty() && !modelName.isEmpty(); }
    @Override public String model() { return host() + "/" + modelName; }

    /** Short provider label for the job record, e.g. {@code groq/llama-3.3-70b-versatile}. */
    String host() {
        String h = baseUrl.replaceFirst("^https?://", "").replaceFirst("[:/].*$", "");
        for (String known : List.of("groq", "openrouter", "mistral", "cerebras", "together", "github", "deepseek", "openai"))
            if (h.contains(known)) return known;
        return h.isEmpty() ? "openai" : h;
    }

    @Override
    public Result generate(Prompt prompt) {
        if (!available()) throw new GenerationException("Chưa cấu hình AI tương thích OpenAI (AI_OPENAI_URL, AI_OPENAI_MODEL)");
        String text = chat(AiPrompts.system(prompt), AiPrompts.user(prompt), 0.7, AiJson.jsonSchema());
        return new Result(AiJson.questions(text), List.of(), model());
    }

    @Override
    public String completeJson(String system, String user) {
        if (!available()) throw new GenerationException("Chưa cấu hình AI tương thích OpenAI");
        return chat(system, user, 0.0);
    }

    private String chat(String system, String user, double temperature) { return chat(system, user, temperature, null); }

    /**
     * Strongest output contract first: a JSON schema (OpenAI/Groq structured outputs), then plain JSON mode, then no
     * constraint (the prompt still spells out the shape). A 400/422 means "not supported here" and moves one step down.
     */
    private String chat(String system, String user, double temperature, Map<String, Object> schema) {
        var formats = new java.util.ArrayList<Map<String, Object>>();
        if (schema != null) formats.add(Map.of("type", "json_schema", "json_schema", Map.of("name", "questions", "schema", schema, "strict", false)));
        formats.add(Map.of("type", "json_object"));
        formats.add(null);
        for (int i = 0; i < formats.size(); i++) {
            try {
                return send(system, user, temperature, formats.get(i));
            } catch (HttpStatusCodeException e) {
                int s = e.getStatusCode().value();
                if ((s == 400 || s == 422) && i < formats.size() - 1) continue;
                throw failure(s, e.getResponseHeaders(), e);
            } catch (RestClientException e) {
                throw new GenerationException(host() + " không phản hồi", true, null, e);
            }
        }
        throw new GenerationException(host() + " từ chối yêu cầu");
    }

    private String send(String system, String user, double temperature, Map<String, Object> format) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isEmpty()) headers.setBearerAuth(apiKey);
        var body = new HashMap<String, Object>();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        if (format != null) body.put("response_format", format);
        byte[] raw = http.postForObject(baseUrl + "/chat/completions", new HttpEntity<>(body, headers), byte[].class);
        return content(raw == null ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8)); // JSON is UTF-8 even without a charset
    }

    /** choices[0].message.content; a refusal or an empty answer is a (non-transient) failure. */
    static String content(String raw) {
        JsonNode root;
        try { root = AiJson.JSON.readTree(raw == null ? "" : raw); }
        catch (Exception e) { throw new GenerationException("AI trả về sai định dạng", e); }
        JsonNode message = root.path("choices").path(0).path("message");
        String text = message.path("content").asText("");
        if (text.isBlank()) throw new GenerationException(message.path("refusal").isTextual() ? "AI từ chối yêu cầu" : "AI không trả về kết quả");
        return text;
    }

    /** 429 and 5xx are transient (with Retry-After when given); 401/403 mean a bad key. */
    GenerationException failure(int status, HttpHeaders headers, Throwable cause) {
        Duration after = null;
        String h = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (h != null) try { after = Duration.ofSeconds((long) Math.ceil(Double.parseDouble(h.strip()))); } catch (NumberFormatException ignored) {}
        String who = host();
        if (status == 429) return new GenerationException(who + " đang bị giới hạn lượt gọi", true, after, cause);
        if (status >= 500) return new GenerationException(who + " tạm thời lỗi (" + status + ")", true, after, cause);
        if (status == 401 || status == 403) return new GenerationException("Khóa API của " + who + " không hợp lệ hoặc không có quyền", false, null, cause);
        if (status == 404) return new GenerationException(who + " không có mô hình " + modelName, false, null, cause);
        if (status == 413) return new GenerationException(who + ": yêu cầu vượt giới hạn token mỗi phút của gói hiện tại (giảm số câu hoặc đổi mô hình)", false, null, cause);
        return new GenerationException(who + " từ chối yêu cầu (" + status + ")", false, null, cause);
    }

}
