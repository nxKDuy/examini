package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredAnswerTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    ExamClient.Question q(String type) {
        return new ExamClient.Question(UUID.randomUUID(), 1, type, "Q", List.of(new ExamClient.Option(a, "A", 0), new ExamClient.Option(b, "B", 1), new ExamClient.Option(c, "C", 2)), BigDecimal.ONE, 0);
    }
    void ok(String type, String content) { SessionService.checkStructured(json, q(type), content); }
    void bad(String type, String content) { assertEquals(400, assertThrows(ResponseStatusException.class, () -> ok(type, content)).getStatusCode().value()); }

    @Test void multipleResponseIsAListOfDistinctOptionIds() {
        ok("MULTIPLE_RESPONSE", "[\"" + a + "\",\"" + c + "\"]");
        ok("MULTIPLE_RESPONSE", "[]");
        bad("MULTIPLE_RESPONSE", a.toString());
        bad("MULTIPLE_RESPONSE", "[\"" + a + "\",\"" + a + "\"]");
        bad("MULTIPLE_RESPONSE", "[\"" + UUID.randomUUID() + "\"]");
        bad("MULTIPLE_RESPONSE", "[1]");
    }

    @Test void trueFalseGroupMapsStatementsToBooleans() {
        ok("TRUE_FALSE_GROUP", "{\"" + a + "\":true,\"" + b + "\":false}");
        ok("TRUE_FALSE_GROUP", "{}");
        bad("TRUE_FALSE_GROUP", "{\"" + a + "\":\"true\"}");
        bad("TRUE_FALSE_GROUP", "{\"" + UUID.randomUUID() + "\":true}");
        bad("TRUE_FALSE_GROUP", "[]");
        bad("TRUE_FALSE_GROUP", "not json");
        ok("ESSAY", "free text {");
    }

    @Test void fillBlankMapsGapsToTextAndMatchingUsesTheListedTargets() {
        ok("FILL_BLANK", "{\"" + a + "\":\"Hà Nội\",\"" + b + "\":\"\"}");
        ok("FILL_BLANK", "{}");
        bad("FILL_BLANK", "{\"" + a + "\":true}");
        bad("FILL_BLANK", "{\"" + UUID.randomUUID() + "\":\"x\"}");
        bad("FILL_BLANK", "{\"" + a + "\":\"" + "x".repeat(501) + "\"}");
        bad("FILL_BLANK", "[]");
        var match = new ExamClient.Question(UUID.randomUUID(), 1, "MATCHING", "Nối", List.of(new ExamClient.Option(a, "Việt Nam", 0), new ExamClient.Option(b, "Pháp", 1)),
                BigDecimal.ONE, 0, Map.of("matchTargets", List.of("Hà Nội", "Paris")));
        SessionService.checkStructured(json, match, "{\"" + a + "\":\"Paris\",\"" + b + "\":\"Paris\"}"); // a target may be picked twice; it scores once
        assertThrows(ResponseStatusException.class, () -> SessionService.checkStructured(json, match, "{\"" + a + "\":\"Tokyo\"}"));
        assertThrows(ResponseStatusException.class, () -> SessionService.checkStructured(json, match, "{\"" + c + "\":\"Paris\"}"));
    }
}
