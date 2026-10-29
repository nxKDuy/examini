package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.service.ImportParser.Flag;
import com.datn.questionbank.service.ai.AiQuestionGenerator.Candidate;
import com.datn.questionbank.service.ai.AiQuestionGenerator.CandidateOption;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiDraftsTest {
    static Candidate mc(String content, Boolean... correct) {
        var options = new ArrayList<CandidateOption>();
        for (int i = 0; i < correct.length; i++) options.add(new CandidateOption("Lựa chọn " + (char) ('A' + i), correct[i]));
        return new Candidate("MULTIPLE_CHOICE", content, options, null, "Vì vậy.", "HARD", "{}");
    }

    @Test void wellFormedQuestionIsKeptButAlwaysNeedsReview() {
        var d = AiDrafts.build(List.of(mc("Thủ đô của Việt Nam là gì?", false, true, false, false)), 1, DifficultyLevel.EASY, List.of());
        var p = d.items().getFirst();
        assertEquals(QuestionType.MULTIPLE_CHOICE, p.type());
        assertEquals(DifficultyLevel.HARD, p.difficulty(), "AI difficulty estimate is kept");
        assertEquals(List.of(Flag.AI_GENERATED), p.flags());
        assertTrue(p.reviewRequired());
        assertEquals(SourceMethod.AI, p.method());
        assertEquals(AiDrafts.BASE_CONFIDENCE, p.confidence());
        assertEquals(List.of((short) 0, (short) 1, (short) 2, (short) 3), p.options().stream().map(o -> o.displayOrder()).toList());
        assertTrue(p.options().get(1).correct());
        assertTrue(d.warnings().isEmpty());
    }

    @Test void missingOrAmbiguousKeyIsFlaggedNeverGuessed() {
        var none = AiDrafts.build(List.of(mc("Câu không có đáp án?", false, null, false)), 1, null, List.of()).items().getFirst();
        assertTrue(none.flags().contains(Flag.MISSING_CORRECT_ANSWER));
        assertTrue(none.options().stream().noneMatch(o -> o.correct()), "no option is promoted to correct");
        assertTrue(none.confidence() < AiDrafts.BASE_CONFIDENCE);

        var many = AiDrafts.build(List.of(mc("Câu hai đáp án?", true, true, false)), 1, null, List.of()).items().getFirst();
        assertTrue(many.flags().contains(Flag.MULTIPLE_ANSWERS));
        assertEquals(2, many.options().stream().filter(o -> o.correct()).count(), "both keys kept for the teacher to fix");
    }

    @Test void duplicatesOfBankOrSiblingsAreFlaggedIgnoringCaseAccentsAndPunctuation() {
        var d = AiDrafts.build(List.of(mc("THU DO cua Viet Nam la gi", false, true), mc("Số nguyên tố nhỏ nhất?", true, false),
                        mc("số nguyên tố   nhỏ nhất", true, false)), 3, null, List.of("Thủ đô của Việt Nam là gì?"));
        assertTrue(d.items().get(0).flags().contains(Flag.POSSIBLE_DUPLICATE));
        assertFalse(d.items().get(1).flags().contains(Flag.POSSIBLE_DUPLICATE));
        assertTrue(d.items().get(2).flags().contains(Flag.POSSIBLE_DUPLICATE));
        assertEquals("thu do cua viet nam la gi", AiDrafts.key("Thủ đô của Việt Nam là gì?"));
    }

    @Test void shapeRulesAndLimits() {
        var choiceWithText = new Candidate("TRUE_FALSE", "Trái Đất quay quanh Mặt Trời.",
                List.of(new CandidateOption("Đúng", true), new CandidateOption("Sai", false)), "Đúng", null, "nope", null);
        var shortNoType = new Candidate(null, "2 + 2 = ?", List.of(), "4", null, null, null);
        var essayNoGuide = new Candidate("essay", "Phân tích vai trò của AI trong giáo dục.", null, null, null, "MEDIUM", null);
        var inferredTf = new Candidate("???", "Nước sôi ở 100°C.", List.of(new CandidateOption("True", true), new CandidateOption("False", false)), null, null, null, null);
        var blank = new Candidate("MULTIPLE_CHOICE", "  ", List.of(), null, null, null, null);
        var d = AiDrafts.build(List.of(choiceWithText, blank, shortNoType, essayNoGuide, inferredTf, mc("Thừa", true, false)), 4,
                DifficultyLevel.MEDIUM, List.of());
        assertEquals(4, d.items().size());
        var tf = d.items().get(0);
        assertEquals(QuestionType.TRUE_FALSE, tf.type());
        assertNull(tf.expectedAnswer(), "choice keys live in options only");
        assertEquals(DifficultyLevel.MEDIUM, tf.difficulty(), "unknown difficulty falls back to the requested level");
        assertEquals("Trái Đất quay quanh Mặt Trời.", tf.rawText(), "raw falls back to the content");
        assertEquals(QuestionType.SHORT_ANSWER, d.items().get(1).type());
        assertEquals("4", d.items().get(1).expectedAnswer());
        assertEquals(QuestionType.ESSAY, d.items().get(2).type());
        assertTrue(d.items().get(2).flags().contains(Flag.NO_ANSWER_ESSAY));
        assertEquals(QuestionType.TRUE_FALSE, d.items().get(3).type());
        assertEquals(List.of("Bỏ qua 1 câu AI trả về không có nội dung", "AI trả về thừa 1 câu; chỉ giữ 4 câu đầu"), d.warnings());

        var duplicateOption = new Candidate("MULTIPLE_CHOICE", "Chọn?", List.of(new CandidateOption("A", true), new CandidateOption(" a ", false)), null, null, null, null);
        assertTrue(AiDrafts.build(List.of(duplicateOption), 1, null, List.of()).items().getFirst().flags().contains(Flag.DUPLICATE_OPTION));

        var tooLong = new Candidate("ESSAY", "x".repeat(6000), null, "guide", null, null, null);
        var cut = AiDrafts.build(List.of(tooLong), 1, null, List.of()).items().getFirst();
        assertEquals(5000, cut.content().length());
        assertTrue(cut.flags().contains(Flag.CONTENT_TRUNCATED));

        assertEquals(List.of("AI chỉ tạo được 0/2 câu"), AiDrafts.build(List.of(), 2, null, List.of()).warnings());
    }
}
