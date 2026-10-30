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
}
