package com.datn.grading.service;

import com.datn.grading.client.AiClient;
import com.datn.grading.dto.SubmissionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnswerTutorTest {
    final UUID session = UUID.randomUUID(), question = UUID.randomUUID(), student = UUID.randomUUID();
    final GradingService grading = mock(GradingService.class);
    final AiClient ai = mock(AiClient.class);

    SubmissionDto.StudentItem item(List<String> key, Float got, String answer) {
        return new SubmissionDto.StudentItem(question, 1, 1, "2 + 3 = ?", "MULTIPLE_CHOICE", 1f, "opt", answer, got, null, key, "Cộng hai số.");
    }
    SubmissionDto.StudentResult result(String status, SubmissionDto.StudentItem item) {
        return new SubmissionDto.StudentResult(session, UUID.randomUUID(), "Toán", 1, status, 3L, null, null, null, 0f, 1f, null, item == null ? List.of() : List.of(item), List.of());
    }
    AnswerTutor tutor(int perHour) { return new AnswerTutor(grading, ai, Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC), perHour); }

    @Test void refusesWhenTheKeyIsHiddenOrResultNotReleased() {
        when(grading.student(session, student)).thenReturn(result("GRADED", item(null, 0f, "4")));
        var e = assertThrows(ResponseStatusException.class, () -> tutor(5).explain(session, question, student, "vi"));
        assertEquals(403, e.getStatusCode().value());
        when(grading.student(session, student)).thenReturn(result("NEEDS_REVIEW", null));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> tutor(5).explain(session, question, student, "vi")).getStatusCode().value());
        verifyNoInteractions(ai);
    }

    @Test void aiAnswerIsCachedAndUsesTheStudentsOwnAnswer() throws Exception {
        when(grading.student(session, student)).thenReturn(result("GRADED", item(List.of("5"), 0f, "4")));
        when(ai.available()).thenReturn(true);
        var prompts = new AtomicInteger();
        when(ai.json(any())).thenAnswer(inv -> {
            prompts.incrementAndGet();
            String p = inv.getArgument(0);
            assertTrue(p.contains("2 + 3 = ?") && p.contains("CORRECT ANSWER\n5") && p.contains("scored 0/1)\n4"), p);
            return Optional.of(new ObjectMapper().readTree("{\"explanation\":\"Bạn đã cộng nhầm: 2 + 3 bằng 5, không phải 4.\",\"tips\":[\"Đếm lại trên ngón tay.\"]}"));
        });
        var t = tutor(5);
        var a = t.explain(session, question, student, "vi");
        assertEquals("AI", a.source());
        assertEquals("WRONG", a.verdict());
        assertEquals(List.of("Đếm lại trên ngón tay."), a.tips());
        t.explain(session, question, student, "vi");
        assertEquals(1, prompts.get(), "second click is served from cache");
    }

    @Test void fallsBackToTheKeyAndTeacherExplanation() {
        when(grading.student(session, student)).thenReturn(result("GRADED", item(List.of("5"), 0f, "")));
        when(ai.available()).thenReturn(true);
        when(ai.json(any())).thenReturn(Optional.empty());
        var a = tutor(5).explain(session, question, student, "vi");
        assertEquals("RULES", a.source());
        assertEquals("UNANSWERED", a.verdict());
        assertTrue(a.explanation().contains("Đáp án đúng: 5") && a.explanation().contains("Cộng hai số."), a.explanation());
    }

    @Test void readingPassageGoesIntoThePrompt() {
        var withPassage = new SubmissionDto.StudentItem(question, 1, 1, "Is c) true?", "TRUE_FALSE_GROUP", 1f, "x", "c) Đúng", 0f, null, List.of("c) — Sai"), null,
                java.util.Map.of("passage", java.util.Map.of("title", "Green cities", "content", "Building green cities is expensive.")));
        String p = AnswerTutor.prompt(withPassage, false);
        assertTrue(p.contains("Green cities\nBuilding green cities is expensive."), p);
        assertTrue(AnswerTutor.prompt(item(List.of("5"), 0f, "4"), false).contains("READING PASSAGE (shared by several questions; the answer may depend on it)\n(none)"));
    }

    @Test void essayPromptGetsThePassageInFront() {
        assertEquals("Viết đoạn văn.", AnswerTutor.withPassage(null, "Viết đoạn văn."));
        assertEquals("Viết đoạn văn.", AnswerTutor.withPassage(java.util.Map.of("media", List.of()), "Viết đoạn văn."));
        assertEquals("READING PASSAGE — Green cities:\nCycle lanes.\n\nTASK:\nViết đoạn văn.",
                AnswerTutor.withPassage(java.util.Map.of("passage", java.util.Map.of("title", "Green cities", "content", " Cycle lanes. ")), "Viết đoạn văn."));
    }

    final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    @Test void practiceQuestionIsShapeCheckedAndKeyGated() throws Exception {
        var om = new ObjectMapper();
        assertTrue(AnswerTutor.practiceOf(om.readTree("{\"question\":\"3 + 4 = ?\",\"options\":[\"6\",\"7\",\"8\",\"12\"],\"answer\":1,\"explanation\":\"Cộng.\"}")).isPresent());
        assertTrue(AnswerTutor.practiceOf(om.readTree("{\"question\":\"3 + 4 = ?\",\"options\":[\"6\",\"7\",\"8\"],\"answer\":1}")).isEmpty(), "needs 4 options");
        assertTrue(AnswerTutor.practiceOf(om.readTree("{\"question\":\"3 + 4 = ?\",\"options\":[\"6\",\"7\",\"7\",\"12\"],\"answer\":1}")).isEmpty(), "options must differ");
        assertTrue(AnswerTutor.practiceOf(om.readTree("{\"question\":\"3 + 4 = ?\",\"options\":[\"6\",\"7\",\"8\",\"12\"],\"answer\":4}")).isEmpty(), "key index in range");
        when(grading.student(session, student)).thenReturn(result("GRADED", item(null, 0f, "4")));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> tutor(5).practice(session, question, student, "vi")).getStatusCode().value());
        when(grading.student(session, student)).thenReturn(result("GRADED", item(List.of("5"), 0f, "4")));
        when(ai.available()).thenReturn(true);
        var good = om.readTree("{\"question\":\"2 + 6 = ?\",\"options\":[\"7\",\"8\",\"9\",\"12\"],\"answer\":1,\"explanation\":\"Cộng.\"}");
        var flawed = om.readTree("{\"question\":\"Số nào chẵn?\",\"options\":[\"2\",\"4\",\"5\",\"7\"],\"answer\":0,\"explanation\":\"x\"}");
        // First draft: the blind solve finds two right options and rejects it; the second draft is confirmed.
        when(ai.json(any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            if (prompt.startsWith("Solve this")) return Optional.of(om.readTree(prompt.contains("chẵn") ? "{\"answer\":\"A\",\"ambiguous\":true}" : "{\"answer\":\"B\",\"ambiguous\":false}"));
            return Optional.of(prompt.contains("ORIGINAL") && inv.getMock() != null && calls.getAndIncrement() == 0 ? flawed : good);
        });
        var p = tutor(5).practice(session, question, student, "vi");
        assertEquals(1, p.answer());
        assertEquals(List.of("7", "8", "9", "12"), p.options());
        assertEquals(2, calls.get(), "the ambiguous draft was replaced");
    }

    @Test void hourlyAllowancePerStudent() {
        var t = tutor(2);
        assertTrue(t.allow(student));
        assertTrue(t.allow(student));
        assertFalse(t.allow(student));
        assertTrue(t.allow(UUID.randomUUID()), "another student has their own allowance");
    }
}
