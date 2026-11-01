package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.ImportParser;
import com.datn.questionbank.service.QuestionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiQuestionReviewTest {
    final UUID id = UUID.randomUUID();
    final QuestionActor teacher = new QuestionActor(UUID.randomUUID(), "TEACHER", false);
    final QuestionService questions = mock(QuestionService.class);

    void stored(boolean keyOnB) {
        var opts = List.of(new ImportParser.Option("3", !keyOnB, (short) 0), new ImportParser.Option("4", keyOnB, (short) 1),
                new ImportParser.Option("5", false, (short) 2), new ImportParser.Option("22", false, (short) 3));
        when(questions.reviewCopy(id, teacher)).thenReturn(new QuestionService.ReviewCopy(new ImportParser.Parsed("2 + 2 bằng bao nhiêu?", QuestionType.MULTIPLE_CHOICE,
                DifficultyLevel.EASY, opts, null, null, null, null, 1, "x", List.of(), false), null));
    }
    /** The model always answers B for the solve and has one remark for the critique. */
    AiQuestionReview review() {
        return new AiQuestionReview(questions, new AiKeyCheck((s, u) -> Optional.of("{\"answers\":[{\"n\":1,\"choose\":[\"B\"]}]}"), true),
                (s, u) -> Optional.of("{\"issues\":[\"Phương án \\\"22\\\" quá vô lý, nên thay bằng 6.\"],\"improvedStem\":\"\"}"));
    }

    @Test void wrongKeyIsReportedFirst() {
        stored(false);
        var r = review().review(id, teacher);
        assertEquals("DISPUTED", r.keyCheck());
        assertTrue(r.issues().stream().anyMatch(i -> i.contains("22")));
        assertNull(r.suggestion(), "an unchanged or empty stem is not offered as a suggestion");
    }

    @Test void rightKeyAgreesAndRuleChecksStillRunWithoutAi() {
        stored(true);
        assertEquals("AGREES", review().review(id, teacher).keyCheck());
        var offline = new AiQuestionReview(questions, new AiKeyCheck((s, u) -> Optional.empty(), true), (s, u) -> Optional.empty());
        var r = offline.review(id, teacher);
        assertEquals("UNCHECKED", r.keyCheck());
        assertFalse(r.aiAvailable());
        assertTrue(r.issues().isEmpty());
    }
}
