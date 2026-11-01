package com.datn.questionbank.service;

import com.datn.questionbank.entity.Category;
import com.datn.questionbank.entity.Question;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.entity.QuestionOption;
import com.datn.questionbank.service.DocumentExtractor.Line;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StructuredTypesTest {
    private static Question q(QuestionType t, String content, String... options) {
        var q = Question.builder().category(new Category()).content(content).questionType(t).difficultyLevel(Question.DifficultyLevel.EASY).build();
        for (int i = 0; i < options.length; i++)
            q.getOptions().add(QuestionOption.builder().question(q).content(options[i]).correct(true).displayOrder((short) i).build());
        return q;
    }

    @Test void blanksPairsAndArrows() {
        assertEquals(2, StructuredTypes.blanks("Thủ đô là ___, năm _____."));
        assertEquals(0, StructuredTypes.blanks("a__b"));
        assertEquals(List.of("TP.HCM", "Sài Gòn"), StructuredTypes.alternatives(" TP.HCM | Sài Gòn |"));
        assertArrayEquals(new String[]{"Việt Nam", "Hà Nội"}, StructuredTypes.pair("Việt Nam => Hà Nội"));
        assertNull(StructuredTypes.pair("Việt Nam =>"));
        assertEquals("Nhật Bản => Tokyo", StructuredTypes.normalizePair("Nhật Bản → Tokyo"));
        assertEquals("H2O => nước", StructuredTypes.normalizePair("H2O -> nước"));
        assertNull(StructuredTypes.normalizePair("Không có mũi tên"));
        assertEquals(List.of("Hà Nội", "1945"), StructuredTypes.gapAnswers("Hà Nội; 1945"));
    }

    @Test void validation() {
        QuestionValidation.validate(q(QuestionType.FILL_BLANK, "Thủ đô là ___, năm ___.", "Hà Nội | Ha Noi", "1945"));
        QuestionValidation.validate(q(QuestionType.FILL_BLANK, "Từ ___ và ___ giống nhau.", "the", "the")); // same answer twice is fine
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(QuestionType.FILL_BLANK, "Thủ đô là ___.", "Hà Nội", "1945")));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(QuestionType.FILL_BLANK, "Không có chỗ trống", "x")));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(QuestionType.FILL_BLANK, "Điền ___", " | ")));
        QuestionValidation.validate(q(QuestionType.MATCHING, "Nối nước với thủ đô", "Việt Nam => Hà Nội", "Pháp => Paris"));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(QuestionType.MATCHING, "Nối", "Việt Nam => Hà Nội")));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(QuestionType.MATCHING, "Nối", "A => 1", "B")));
        assertThrows(IllegalArgumentException.class, () -> QuestionValidation.validate(q(QuestionType.MATCHING, "Nối", "A => 1", "B => 1")));
        assertTrue(QuestionValidation.isChoice(QuestionType.MATCHING));
    }

    @Test void typeWords() {
        assertEquals(QuestionType.FILL_BLANK, SheetImport.type("Điền vào chỗ trống", false));
        assertEquals(QuestionType.FILL_BLANK, SheetImport.type("Điền khuyết", false));
        assertEquals(QuestionType.MATCHING, SheetImport.type("Nối cột", true));
        assertEquals(QuestionType.SPEAKING, SheetImport.type("Nói", false));
        assertEquals(QuestionType.SHORT_ANSWER, SheetImport.type("Trả lời ngắn", false));
    }

    private static Line l(String t) { return new Line(t, 1, SourceMethod.TEXT, null); }

    @Test void wordImportReadsBothTypes() {
        var parsed = ImportParser.parse(List.of(
                l("Câu 1: Thủ đô của Việt Nam là ___, Cách mạng tháng Tám thành công năm ___."), l("Loại: Điền vào chỗ trống"), l("Đáp án: Hà Nội; 1945"),
                l("Câu 2: Nối mỗi nước với thủ đô."), l("A. Việt Nam => Hà Nội"), l("B. Nhật Bản → Tokyo"), l("C. Pháp -> Paris"),
                l("Câu 3: Năm ___ và năm ___ là hai mốc."), l("Đáp án: 1945; 1975")), Question.DifficultyLevel.MEDIUM);
        assertEquals(3, parsed.size());
        var fill = parsed.get(0);
        assertEquals(QuestionType.FILL_BLANK, fill.type());
        assertEquals(List.of("Hà Nội", "1945"), fill.options().stream().map(ImportParser.Option::content).toList());
        assertTrue(fill.options().stream().allMatch(ImportParser.Option::correct));
        assertFalse(fill.flags().contains(ImportParser.Flag.MISSING_CORRECT_ANSWER));
        var match = parsed.get(1);
        assertEquals(QuestionType.MATCHING, match.type());
        assertEquals(List.of("Việt Nam => Hà Nội", "Nhật Bản => Tokyo", "Pháp => Paris"), match.options().stream().map(ImportParser.Option::content).toList());
        assertEquals(QuestionType.FILL_BLANK, parsed.get(2).type()); // inferred: 2 gaps, 2 answers
    }

    @Test void sheetImportReadsBothTypes() throws Exception {
        var parsed = SheetImport.parse(ImportTemplates.excel(), false, Question.DifficultyLevel.MEDIUM);
        var fill = parsed.stream().filter(p -> p.type() == QuestionType.FILL_BLANK).findFirst().orElseThrow();
        assertEquals(List.of("Hà Nội", "1945"), fill.options().stream().map(ImportParser.Option::content).toList());
        var match = parsed.stream().filter(p -> p.type() == QuestionType.MATCHING).findFirst().orElseThrow();
        assertEquals(4, match.options().size());
        assertFalse(match.reviewRequired());
    }
}
