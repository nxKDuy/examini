package com.datn.questionbank.service.ai;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real shapes returned by schema-less OpenAI-compatible models (Groq gpt-oss, Oct 2026). */
class AiJsonTest {
    @Test void canonicalShape() {
        var q = AiJson.questions("{\"questions\":[{\"type\":\"MULTIPLE_CHOICE\",\"content\":\"2+2?\",\"options\":[{\"content\":\"4\",\"correct\":true},{\"content\":\"5\",\"correct\":false}],\"explanation\":\"x\",\"difficulty\":\"EASY\"}]}").getFirst();
        assertEquals("2+2?", q.content());
        assertEquals(Arrays.asList(true, false), q.options().stream().map(AiQuestionGenerator.CandidateOption::correct).toList());
    }

    @Test void plainStringOptionsWithTextAnswer() {
        var list = AiJson.questions("{\"questions\":[{\"type\":\"MULTIPLE_CHOICE\",\"question\":\"Cho hàm f(x)=x^3-3x+2. Giá trị của f'(2) là:\",\"options\":[\"6\",\"9\",\"12\",\"15\"],\"answer\":\"9\"},"
                + "{\"type\":\"SHORT_ANSWER\",\"question\":\"Tính f'(x)\",\"answer\":\"f'(x)=2x\"}]}");
        var mc = list.get(0);
        assertEquals("Cho hàm f(x)=x^3-3x+2. Giá trị của f'(2) là:", mc.content());
        assertEquals(List.of("6", "9", "12", "15"), mc.options().stream().map(AiQuestionGenerator.CandidateOption::content).toList());
        assertEquals(Arrays.asList(false, true, false, false), mc.options().stream().map(AiQuestionGenerator.CandidateOption::correct).toList());
        assertNull(mc.expectedAnswer(), "choice keys live in options");
        assertEquals("f'(x)=2x", list.get(1).expectedAnswer());
        assertTrue(list.get(1).options().isEmpty());
    }

    @Test void labelledChoicesWithLetterAnswer() {
        var q = AiJson.questions("{\"questions\":[{\"id\":\"Q1\",\"type\":\"MULTIPLE_CHOICE\",\"question\":\"f'(1)?\",\"choices\":[{\"label\":\"A\",\"text\":\"5\"},{\"label\":\"B\",\"text\":\"2\"},{\"label\":\"C\",\"text\":\"-2\"},{\"label\":\"D\",\"text\":\"0\"}],\"answer\":\"D\"}]}").getFirst();
        assertEquals(List.of("5", "2", "-2", "0"), q.options().stream().map(AiQuestionGenerator.CandidateOption::content).toList());
        assertEquals(Arrays.asList(false, false, false, true), q.options().stream().map(AiQuestionGenerator.CandidateOption::correct).toList());
    }

    @Test void multipleAnswersAndPrefixedOptions() {
        var q = AiJson.questions("[{\"type\":\"MULTIPLE_RESPONSE\",\"stem\":\"Chọn các số chẵn\",\"options\":[\"A. 2\",\"B. 3\",\"C. 4\"],\"correctAnswers\":[\"A\",\"C\"]}]").getFirst();
        assertEquals("Chọn các số chẵn", q.content());
        assertEquals(List.of("2", "3", "4"), q.options().stream().map(AiQuestionGenerator.CandidateOption::content).toList());
        assertEquals(Arrays.asList(true, false, true), q.options().stream().map(AiQuestionGenerator.CandidateOption::correct).toList());
    }

    @Test void unmatchedAnswerIsNotGuessed() {
        var q = AiJson.questions("{\"questions\":[{\"question\":\"?\",\"options\":[\"x\",\"y\"],\"answer\":\"z\"}]}").getFirst();
        assertEquals(Arrays.asList(false, false), q.options().stream().map(AiQuestionGenerator.CandidateOption::correct).toList());
        var none = AiJson.questions("{\"questions\":[{\"question\":\"?\",\"options\":[\"x\",\"y\"]}]}").getFirst();
        assertEquals(Arrays.asList(null, null), none.options().stream().map(AiQuestionGenerator.CandidateOption::correct).toList());
    }
}
