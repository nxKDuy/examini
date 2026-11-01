package com.datn.grading.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** OpenAI-compatible and Ollama providers against a local stub; Gemini is off (no key). */
class AiClientTest {
    HttpServer server;
    final Map<String, String> replies = new ConcurrentHashMap<>();
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    AiClient ai;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            bodies.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " AUTH=" + ex.getRequestHeaders().getFirst("Authorization"));
            String r = replies.get(ex.getRequestURI().getPath());
            byte[] out = (r == null ? "{}" : r).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r == null ? 503 : 200, out.length);
            ex.getResponseBody().write(out); ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ai = new AiClient();
        ai.geminiKey = "";
        ai.openaiUrl = base + "/v1/"; ai.openaiKey = "gsk_free"; ai.openaiModel = "llama-3.3-70b-versatile";
        ai.ollamaUrl = base; ai.ollamaModel = "qwen2.5:7b-instruct";
        ai.timeoutSeconds = 5;
    }
    @AfterEach void stop() { server.stop(0); }

    static String chat(String content) { return "{\"choices\":[{\"message\":{\"content\":" + json(content) + "}}]}"; }
    static String json(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @Test void essaySuggestionUsesTheMarkingGuideAndTheFirstWorkingProvider() {
        replies.put("/v1/chat/completions", chat("{\"score\":0.75,\"feedback\":\"Đúng ý chính, thiếu ví dụ.\"}"));
        var s = ai.gradeEssay("Nêu định nghĩa dao động điều hòa", "x = A cos(ωt + φ)", "Là dao động lặp lại", 2f);
        assertEquals(0.75f, s.score());
        assertEquals("openai:llama-3.3-70b-versatile", s.model());
        String sent = bodies.getFirst();
        assertTrue(sent.contains("x = A cos") && sent.contains("worth 2 points") && sent.contains("json_object") && sent.endsWith("AUTH=Bearer gsk_free"));
    }

    @Test void outOfRangeOrBrokenAnswersFallThroughToTheNextProvider() {
        replies.put("/v1/chat/completions", chat("{\"score\":7}"));
        replies.put("/api/generate", "{\"response\":\"{\\\"score\\\":0.5,\\\"feedback\\\":\\\"Tạm được\\\"}\"}");
        var s = ai.gradeEssay("Q", null, "A", 1f);
        assertEquals("ollama:qwen2.5:7b-instruct", s.model());
        assertTrue(bodies.getFirst().contains("(none)"));

        replies.clear();
        assertThrows(IllegalStateException.class, () -> ai.gradeEssay("Q", null, "A", 1f));
        assertTrue(ai.json("{}").isEmpty());
    }

    @Test void nothingConfiguredMeansUnavailable() {
        var off = new AiClient();
        off.geminiKey = "demo-key"; off.openaiUrl = ""; off.ollamaUrl = "";
        assertFalse(off.available());
        assertTrue(off.providers("p", 0).isEmpty());
    }
}
