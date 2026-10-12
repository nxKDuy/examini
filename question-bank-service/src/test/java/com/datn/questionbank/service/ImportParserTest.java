package com.datn.questionbank.service;

import com.datn.questionbank.entity.Question.*;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.service.DocumentExtractor.Line;
import com.datn.questionbank.service.ImportParser.Flag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportParserTest {
    static List<Line> text(String document) {
        var lines = new ArrayList<Line>();int page = 1;
        for (String l : document.split("\n")) { if (l.equals("<page>")) { page++; continue; } if (!l.isBlank()) lines.add(new Line(l.strip(), page, SourceMethod.TEXT, null)); }
        return lines;
    }

    @Test void parsesVietnameseTemplateWithMultilineContentExplanationAndDifficulty() {
        var items = ImportParser.parse(text("""
                ĐỀ KIỂM TRA 15 PHÚT
                Câu 1: Thủ đô của Việt Nam là
                thành phố nào?
                A. Huế
                B. Hà Nội
                C. Đà Nẵng
                D. TP. Hồ Chí Minh
                Đáp án: B
                Giải thích: Hà Nội là thủ đô
                từ năm 1010.
                Mức độ: Dễ
                <page>
                Câu 2. 2 + 2 = ?
                A. 3   B. 4   C. 5   D. 6
                Đáp án: B. 4
                """), DifficultyLevel.MEDIUM);
        assertEquals(2, items.size());
        var first = items.getFirst();
        assertEquals("Thủ đô của Việt Nam là\nthành phố nào?", first.content());
        assertEquals(QuestionType.MULTIPLE_CHOICE, first.type());
        assertEquals(DifficultyLevel.EASY, first.difficulty());
        assertEquals(List.of(false, true, false, false), first.options().stream().map(ImportParser.Option::correct).toList());
        assertEquals("Hà Nội là thủ đô\ntừ năm 1010.", first.explanation());
        assertEquals(1, first.page()); assertEquals(1.0, first.confidence()); assertTrue(first.flags().isEmpty()); assertFalse(first.reviewRequired());
        var second = items.get(1);
        assertEquals(2, second.page()); assertEquals(DifficultyLevel.MEDIUM, second.difficulty());
        assertEquals(List.of("3", "4", "5", "6"), second.options().stream().map(ImportParser.Option::content).toList());
        assertTrue(second.options().get(1).correct());
    }

    @Test void neverGuessesMissingOrUnmatchedAnswers() {
        var items = ImportParser.parse(text("""
                Câu 1: Không có đáp án
                A. Một
                B. Hai
                Câu 2: Đáp án ngoài lựa chọn
                A. Một
                B. Hai
                Đáp án: E
                Câu 3: Hai đáp án
                A. Một
                B. Hai
                C. Ba
                Đáp án: A và C
                """), DifficultyLevel.MEDIUM);
        assertTrue(items.get(0).options().stream().noneMatch(ImportParser.Option::correct));
        assertTrue(items.get(0).flags().contains(Flag.MISSING_CORRECT_ANSWER));
        assertFalse(items.get(0).reviewRequired());
        assertTrue(items.get(1).options().stream().noneMatch(ImportParser.Option::correct));
        assertTrue(items.get(1).flags().contains(Flag.ANSWER_NOT_IN_OPTIONS)); assertTrue(items.get(1).reviewRequired());
        assertEquals(List.of(true, false, true), items.get(2).options().stream().map(ImportParser.Option::correct).toList());
        assertTrue(items.get(2).flags().contains(Flag.MULTIPLE_ANSWERS));
        assertTrue(items.get(2).confidence() < 1.0);
    }

    @Test void detectsTrueFalseShortAnswerEssayAndEnglishKeywords() {
        var items = ImportParser.parse(text("""
                Question 1: The earth is round.
                A. True
                B. False
                Answer: True
                Question 2: Capital of France?
                Answer: Paris
                Explanation: Paris has been the capital since 987.
                Question 3: Describe the water cycle.
                Difficulty: HARD
                """), DifficultyLevel.MEDIUM);
        assertEquals(QuestionType.TRUE_FALSE, items.get(0).type()); assertTrue(items.get(0).options().get(0).correct());
        assertEquals(QuestionType.SHORT_ANSWER, items.get(1).type()); assertEquals("Paris", items.get(1).expectedAnswer());
        assertEquals("Paris has been the capital since 987.", items.get(1).explanation());
        assertEquals(QuestionType.ESSAY, items.get(2).type()); assertEquals(DifficultyLevel.HARD, items.get(2).difficulty());
        assertTrue(items.get(2).flags().contains(Flag.NO_ANSWER_ESSAY)); assertTrue(items.get(2).reviewRequired());
    }

    @Test void ocrItemsCarryTesseractConfidenceAndAlwaysNeedReview() {
        var lines = List.of(new Line("Câu 1: Hai cộng hai?", 3, SourceMethod.OCR, 90.0), new Line("A. 3", 3, SourceMethod.OCR, 70.0),
                new Line("B. 4", 3, SourceMethod.OCR, 80.0), new Line("Đáp án: B", 3, SourceMethod.OCR, 80.0));
        var item = ImportParser.parse(lines, DifficultyLevel.MEDIUM).getFirst();
        assertEquals(SourceMethod.OCR, item.method()); assertEquals(3, item.page()); assertEquals(0.8, item.confidence());
        assertTrue(item.flags().contains(Flag.OCR)); assertTrue(item.reviewRequired()); assertTrue(item.options().get(1).correct());
    }

    @Test void flagsOptionSequenceGapsAndDuplicates() {
        var item = ImportParser.parse(text("""
                Câu 1: Thiếu B
                A. Một
                C. Ba
                C. Bốn
                Đáp án: A
                """), DifficultyLevel.MEDIUM).getFirst();
        assertTrue(item.flags().containsAll(List.of(Flag.OPTION_SEQUENCE_GAP, Flag.DUPLICATE_OPTION, Flag.LOW_CONFIDENCE)));
    }

    @Test void tesseractTsvGroupsWordsIntoLinesWithMeanConfidence() {
        String tsv = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                + "5\t1\t1\t1\t1\t1\t0\t0\t1\t1\t90\tCâu\n5\t1\t1\t1\t1\t2\t0\t0\t1\t1\t70\t1:\n"
                + "4\t1\t1\t1\t2\t0\t0\t0\t1\t1\t-1\t\n5\t1\t1\t1\t2\t1\t0\t0\t1\t1\t60\tA.\n";
        var lines = DocumentExtractor.tsv(tsv, 2);
        assertEquals(List.of("Câu 1:", "A."), lines.stream().map(Line::text).toList());
        assertEquals(80.0, lines.getFirst().confidence()); assertEquals(2, lines.getFirst().page());
    }
}
