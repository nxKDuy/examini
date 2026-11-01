package com.datn.analytics.insight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiWriterTest {
    final AiWriter ai = new AiWriter();

    @Test void acceptsOnlyTheExpectedShape() throws Exception {
        var w = ai.parse("```json\n{\"summary\":\"Lớp làm khá tốt.\",\"strengths\":[\"Câu 1 tốt\",\"\"],\"concerns\":\"x\",\"actions\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}\n```", "gemini:x");
        assertEquals("Lớp làm khá tốt.", w.summary());
        assertEquals(1, w.strengths().size());
        assertTrue(w.concerns().isEmpty());
        assertEquals(5, w.actions().size());
        assertNull(ai.parse("{\"summary\":\"\"}", "m"));
        assertThrows(Exception.class, () -> ai.parse("not json", "m"));
        assertFalse(ai.available());
        assertTrue(ai.write("x", java.util.Map.of()).isEmpty());
    }

    @Test void openAiCompatibleProviderWritesTheReading() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/chat/completions", ex -> {
            seen.set(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + " AUTH=" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = "{\"choices\":[{\"message\":{\"content\":\"{\\\"summary\\\":\\\"Lớp đạt mức khá.\\\",\\\"actions\\\":[\\\"Ôn câu 4\\\"]}\"}}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.start();
        try {
            var w = new AiWriter();
            w.openaiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            w.openaiKey = "k"; w.openaiModel = "llama-3.3-70b-versatile";
            assertTrue(w.available());
            var r = w.write("Viết nhận xét", java.util.Map.of("avg", 61.9)).orElseThrow();
            assertEquals("Lớp đạt mức khá.", r.summary());
            assertEquals("openai:llama-3.3-70b-versatile", r.model());
            assertTrue(seen.get().contains("61.9") && seen.get().endsWith("AUTH=Bearer k"));
        } finally { server.stop(0); }
    }
}
