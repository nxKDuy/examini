package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/** Gemini, Ollama and Wikipedia clients against a local HTTP stub (no network). */
class HttpProvidersTest {
    HttpServer server;
    final Map<String, String[]> responses = new ConcurrentHashMap<>(); // path -> {status, body, retryAfter}
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    String base;
    static final Prompt PROMPT = new Prompt("Nước", "Nước sôi ở 100 độ C.", List.of(), 1, List.of(QuestionType.SHORT_ANSWER), DifficultyLevel.EASY, "vi");

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestURI().getPath();
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " UA=" + ex.getRequestHeaders().getFirst("User-Agent"));
            String[] r = responses.getOrDefault(key, new String[]{"404", "{}", null});
            if (key.endsWith("api.php")) r = responses.getOrDefault(key + "?" + (ex.getRequestURI().getQuery().contains("list=search") ? "search" : "extract"), r);
            if (r[2] != null) ex.getResponseHeaders().add("Retry-After", r[2]);
            byte[] body = r[1].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(Integer.parseInt(r[0]), body.length);
            ex.getResponseBody().write(body); ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    static final String QUESTIONS = "{\\\"questions\\\":[{\\\"type\\\":\\\"SHORT_ANSWER\\\",\\\"content\\\":\\\"Nước sôi ở bao nhiêu độ C?\\\",\\\"expectedAnswer\\\":\\\"100\\\",\\\"explanation\\\":\\\"x\\\",\\\"difficulty\\\":\\\"EASY\\\"}]}";

    @Test void geminiSuccessRateLimitAndBadKey() {
        var g = new GeminiQuestionGenerator("k", "m1", 10, base + "/v1beta/models/%s:generateContent");
        responses.put("/v1beta/models/m1:generateContent", new String[]{"200", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + QUESTIONS + "\"}]},\"finishReason\":\"STOP\"}]}", null});
        var r = g.generate(PROMPT);
        assertEquals("gemini/m1", r.model());
        assertEquals("100", r.candidates().getFirst().expectedAnswer());
        assertTrue(requests.getFirst().contains("\"responseSchema\""));

        responses.put("/v1beta/models/m1:generateContent", new String[]{"429", "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}", "42"});
        var limited = assertThrows(GenerationException.class, () -> g.generate(PROMPT));
        assertTrue(limited.transientFailure()); assertEquals(Duration.ofSeconds(42), limited.retryAfter());
        assertEquals("Gemini đang bị giới hạn lượt gọi", limited.getMessage());

        responses.put("/v1beta/models/m1:generateContent", new String[]{"403", "{}", null});
        var denied = assertThrows(GenerationException.class, () -> g.generate(PROMPT));
        assertFalse(denied.transientFailure());
        responses.put("/v1beta/models/m1:generateContent", new String[]{"503", "{}", null});
        assertTrue(assertThrows(GenerationException.class, () -> g.generate(PROMPT)).transientFailure());
    }

    @Test void ollamaStructuredOutputAndMissingModel() {
        var o = new OllamaQuestionGenerator(base + "/", "qwen2.5:7b-instruct", 10);
        assertTrue(o.available());
        responses.put("/api/chat", new String[]{"200", "{\"message\":{\"role\":\"assistant\",\"content\":\"" + QUESTIONS + "\"},\"done\":true,\"done_reason\":\"stop\"}", null});
        var r = o.generate(PROMPT);
        assertEquals("ollama/qwen2.5:7b-instruct", r.model());
        assertEquals("Nước sôi ở bao nhiêu độ C?", r.candidates().getFirst().content());
        String sent = requests.getFirst();
        assertTrue(sent.contains("\"format\":{") && sent.contains("\"stream\":false") && sent.contains("\"role\":\"system\""), sent);

        responses.put("/api/chat", new String[]{"404", "{\"error\":\"model not found\"}", null});
        var missing = assertThrows(GenerationException.class, () -> o.generate(PROMPT));
        assertTrue(missing.getMessage().contains("ollama pull qwen2.5:7b-instruct"));
        assertFalse(new OllamaQuestionGenerator("", "x", 10).available());
        server.stop(0);
        assertTrue(assertThrows(GenerationException.class, () -> o.generate(PROMPT)).transientFailure(), "connection refused is transient");
    }

    @Test void wikipediaSearchExtractsAndRateLimit() {
        var w = new WikipediaClient(base + "/w/api.php", "EXAMINI-test/1.0");
        responses.put("/w/api.php?search", new String[]{"200", "{\"query\":{\"search\":[{\"title\":\"Nước\"}]}}", null});
        responses.put("/w/api.php?extract", new String[]{"200", "{\"query\":{\"pages\":{\"1\":{\"title\":\"Nước\",\"extract\":\"Nước là hợp chất hóa học của oxy và hydro. Nước sôi ở 100 độ C.\"}}}}", null});
        var found = w.search("nước", "vi");
        assertNull(found.warning());
        assertEquals("Nước", found.pages().getFirst().title());
        assertEquals("https://127.0.0.1/wiki/Nước", found.pages().getFirst().url());
        assertTrue(requests.stream().allMatch(r -> r.contains("UA=EXAMINI-test/1.0")), "identifies itself as Wikimedia asks");

        responses.put("/w/api.php?search", new String[]{"429", "too many", null});
        var limited = w.search("nước", "vi");
        assertTrue(limited.pages().isEmpty());
        assertEquals("Wikipedia đang giới hạn lượt truy cập; bỏ qua tìm kiến thức tự động", limited.warning());

        var retriever = new KnowledgeRetriever(new WikipediaClient(base + "/w/api.php", "x"), true);
        responses.put("/w/api.php?search", new String[]{"200", "{\"query\":{\"search\":[{\"title\":\"Nước\"}]}}", null});
        var k = retriever.retrieve("nước", null, "vi", true);
        assertTrue(k.text().startsWith("[1] Wikipedia – Nước: Nước là hợp chất"), k.text());
        assertEquals("https://127.0.0.1/wiki/Nước", k.sources().getFirst().url());
        assertNull(new KnowledgeRetriever(new WikipediaClient(base + "/w/api.php", "x"), false).retrieve("nước", null, "vi", true).text(), "disabled on the server");
    }

    @Test void openAiCompatibleSuccessFallbackRateLimitAndCheck() {
        var g = new OpenAiCompatibleQuestionGenerator(base + "/openai/v1/", "sk-free", "llama-3.3-70b-versatile", 10);
        assertTrue(g.available());
        assertEquals("127.0.0.1/llama-3.3-70b-versatile", g.model());
        assertEquals("groq", new OpenAiCompatibleQuestionGenerator("https://api.groq.com/openai/v1", "k", "m", 10).host());
        assertEquals("openrouter", new OpenAiCompatibleQuestionGenerator("https://openrouter.ai/api/v1", "k", "m", 10).host());
        assertFalse(new OpenAiCompatibleQuestionGenerator("", "k", "m", 10).available());

        String ok = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + QUESTIONS + "\"}}]}";
        responses.put("/openai/v1/chat/completions", new String[]{"200", ok, null});
        var r = g.generate(PROMPT);
        assertEquals("100", r.candidates().getFirst().expectedAnswer());
        assertTrue(requests.getFirst().contains("\"response_format\""));
        assertTrue(requests.getFirst().contains("llama-3.3-70b-versatile"));

        responses.put("/openai/v1/chat/completions", new String[]{"429", "{}", "7"});
        var limited = assertThrows(GenerationException.class, () -> g.generate(PROMPT));
        assertTrue(limited.transientFailure());
        assertEquals(Duration.ofSeconds(7), limited.retryAfter());

        responses.put("/openai/v1/chat/completions", new String[]{"401", "{}", null});
        assertFalse(assertThrows(GenerationException.class, () -> g.generate(PROMPT)).transientFailure());

        responses.put("/openai/v1/chat/completions", new String[]{"200", "{\"choices\":[{\"message\":{\"content\":\"{\\\"answers\\\":[]}\"}}]}", null});
        assertEquals("{\"answers\":[]}", g.completeJson("s", "u"));
        assertTrue(requests.getLast().contains("\"temperature\":0.0"));
    }

    @Test void openAiCompatibleRetriesWithoutJsonModeWhenRejected() {
        var g = new OpenAiCompatibleQuestionGenerator(base + "/v1", "", "local-model", 10);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.removeContext("/");
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body + " AUTH=" + ex.getRequestHeaders().getFirst("Authorization"));
            boolean json = body.contains("response_format");
            calls.incrementAndGet();
            byte[] out = (json ? "{\"error\":\"response_format not supported\"}"
                    : "{\"choices\":[{\"message\":{\"content\":\"```json\\n" + QUESTIONS + "\\n```\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(json ? 400 : 200, out.length);
            ex.getResponseBody().write(out); ex.close();
        });
        assertEquals("100", g.generate(PROMPT).candidates().getFirst().expectedAnswer());
        assertEquals(3, calls.get(), "schema, then JSON mode, then plain");
        assertTrue(requests.getFirst().endsWith("AUTH=null"), "no key → no Authorization header");
    }
}
