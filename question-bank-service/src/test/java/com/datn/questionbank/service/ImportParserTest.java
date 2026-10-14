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

    static List<ImportParser.Parsed> ocr(String document) {
        var lines = new ArrayList<Line>();
        for (String l : document.split("\n")) if (!l.isBlank()) lines.add(new Line(l.strip(), 1, SourceMethod.OCR, 92.0));
        return ImportParser.parse(lines, DifficultyLevel.MEDIUM);
    }
    static List<String> contents(List<ImportParser.Parsed> items) { return items.stream().map(ImportParser.Parsed::content).toList(); }

    @Test void splitsQuestionsMergedOnOneOcrLine() {
        var items = ocr("""
                Câu 1: 2 + 2 = ? A. 3 B. 4 C. 5 D. 6 Đáp án: B Câu 2: Thủ đô Việt Nam? A. Huế B. Hà Nội Đáp án: B
                Giải thích: Hà Nội. Câu 3. Nước sôi ở bao nhiêu độ?
                A. 90 B. 100
                Đáp án: B
                """);
        assertEquals(List.of("2 + 2 = ?", "Thủ đô Việt Nam?", "Nước sôi ở bao nhiêu độ?"), contents(items));
        assertEquals(List.of("3", "4", "5", "6"), items.get(0).options().stream().map(ImportParser.Option::content).toList());
        assertTrue(items.get(0).options().get(1).correct());
        assertTrue(items.get(1).options().get(1).correct()); assertEquals("Hà Nội.", items.get(1).explanation());
        assertTrue(items.get(2).options().get(1).correct());
        for (var item : items) assertFalse(item.flags().contains(Flag.POSSIBLE_MERGED_QUESTIONS), item.flags().toString());
    }

    @Test void toleratesOcrVariantsOfTheQuestionKeyword() {
        var items = ocr("""
                Cau 1: Một
                A. x
                B. y
                Đáp án: A
                CÂU 2. Hai
                A. x
                B. y
                Đáp án: B
                Câu3: Ba
                A. x
                B. y
                Đáp án: A
                Càu 4) Bốn
                A. x
                B. y
                Đáp án: B
                """);
        assertEquals(List.of("Một", "Hai", "Ba", "Bốn"), contents(items));
        for (var item : items) { assertFalse(item.flags().contains(Flag.NUMBERING_GAP)); assertFalse(item.flags().contains(Flag.QUESTION_SPLIT_UNCERTAIN)); }
    }

    @Test void numericStartsSplitOnlyInSequenceAndOtherwiseFlag() {
        var items = ocr("""
                1. Sắp xếp các bước:
                A. Rửa B. Nấu
                Đáp án: A 2. Tính 3 + 3
                A. 5 B. 6
                Đáp án: B
                5. bước phụ
                """);
        assertEquals(List.of("Sắp xếp các bước:", "Tính 3 + 3"), contents(items));
        assertTrue(items.get(0).options().get(0).correct()); assertTrue(items.get(1).options().get(1).correct());
        assertTrue(items.get(1).flags().contains(Flag.QUESTION_SPLIT_UNCERTAIN), "out-of-sequence 5. stays text but is flagged");
        assertTrue(items.get(1).reviewRequired());

        var keyworded = ocr("""
                Câu 1: Đề bài
                A. x B. y
                Đáp án: A 2. Câu tiếp theo bị mất chữ Câu
                A. m B. n
                Đáp án: B
                """);
        assertEquals(List.of("Đề bài", "Câu tiếp theo bị mất chữ Câu"), contents(keyworded));
        assertTrue(keyworded.get(1).flags().contains(Flag.QUESTION_SPLIT_UNCERTAIN), "a split resting only on N. is never silent");
    }

    @Test void lostHeaderIsFlaggedNotGuessed() {
        var items = ocr("""
                Câu 1: Một cộng một?
                A. 1
                B. 2
                Đáp án: B
                Hai cộng hai?
                A. 3
                B. 4
                Đáp án: B
                """);
        assertEquals(1, items.size());
        var item = items.getFirst();
        assertTrue(item.flags().contains(Flag.POSSIBLE_MERGED_QUESTIONS));
        assertTrue(item.reviewRequired()); assertTrue(item.confidence() < ImportParser.REVIEW_THRESHOLD);
        assertEquals(List.of(false, true), item.options().stream().limit(2).map(ImportParser.Option::correct).toList(), "first answer kept, second not applied");
    }

    @Test void numberingGapsAndLetterForDigitAreFlagged() {
        var items = ocr("""
                Câu l: Chữ l thay cho số 1
                A. x
                B. y
                Đáp án: A
                Câu 3: Thiếu câu 2
                A. x
                B. y
                Đáp án: B
                """);
        assertEquals(2, items.size());
        assertTrue(items.get(0).flags().contains(Flag.QUESTION_SPLIT_UNCERTAIN));
        assertTrue(items.get(1).flags().contains(Flag.NUMBERING_GAP));
    }

    @Test void proseMentionOfAQuestionNumberIsNotASplit() {
        var items = ocr("""
                Câu 1: Dựa vào kết quả câu 2: hãy chọn
                A. x
                B. y
                Đáp án: A
                """);
        assertEquals(List.of("Dựa vào kết quả câu 2: hãy chọn"), contents(items));
    }

    @Test void ocrMarkerSpacingAndTrailingJunkDoNotBreakOptionsOrAnswer() {
        var item = ocr("""
                Câu 1: Nước sôi ở bao nhiêu độ?
                A.90 B.100
                Đáp án:B_
                """).getFirst();
        assertEquals(List.of("90", "100"), item.options().stream().map(ImportParser.Option::content).toList());
        assertTrue(item.options().get(1).correct()); assertFalse(item.flags().contains(Flag.ANSWER_NOT_IN_OPTIONS));
    }
}
