package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ImportParser.Flag;
import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalQuestionGeneratorTest {
    static final String HISTORY = """
            Năm 1010, vua Lý Thái Tổ dời đô từ Hoa Lư về Đại La và đổi tên thành Thăng Long.
            Chiếu dời đô được ban hành để giải thích lý do chọn Đại La làm kinh đô mới của đất nước.
            Năm 1802, Nguyễn Ánh lên ngôi hoàng đế và chọn Phú Xuân làm kinh đô của triều Nguyễn.
            Năm 1945, Chủ tịch Hồ Chí Minh đọc Tuyên ngôn độc lập tại Quảng trường Ba Đình ở Hà Nội.
            Thành phố Hà Nội có diện tích khoảng 3.359 km² và dân số hơn 8 triệu người theo thống kê gần đây.
            Văn Miếu được xây dựng năm 1070 và được xem là trường đại học đầu tiên của Việt Nam.
            """;
    final LocalQuestionGenerator gen = new LocalQuestionGenerator();
    Prompt prompt(String material, int count, QuestionType... types) {
        return new Prompt("Lịch sử kinh đô Việt Nam", material, List.of(), count, List.of(types), DifficultyLevel.MEDIUM, "vi");
    }

    @Test void multipleChoiceIsAClozeWithOneKeyAndThreeDistinctDistractorsOfTheSameKind() {
        var r = gen.generate(prompt(HISTORY, 4, QuestionType.MULTIPLE_CHOICE));
        assertEquals(LocalQuestionGenerator.MODEL, r.model());
        assertFalse(r.candidates().isEmpty());
        for (var c : r.candidates()) {
            assertEquals("MULTIPLE_CHOICE", c.type());
            assertTrue(c.content().contains("_____"), c.content());
            assertEquals(4, c.options().size());
            assertEquals(1, c.options().stream().filter(o -> Boolean.TRUE.equals(o.correct())).count());
            assertEquals(4, c.options().stream().map(o -> LocalQuestionGenerator.fold(o.content())).distinct().count(), "options differ");
            String key = c.options().stream().filter(o -> Boolean.TRUE.equals(o.correct())).findFirst().orElseThrow().content();
            assertTrue(c.explanation().contains(key), "the explanation quotes the source sentence holding the key");
            assertTrue(c.raw().contains("\"generator\": \"local/rule-based\""));
        }
        // Built questions pass the same drafting rules as LLM output: no structural flags.
        var drafts = AiDrafts.build(r.candidates(), 4, DifficultyLevel.MEDIUM, List.of());
        assertTrue(drafts.items().stream().allMatch(i -> i.flags().equals(List.of(Flag.AI_GENERATED))), drafts.items().toString());
    }

    @Test void everyTypeFollowsItsShapeAndTheRequestedMix() {
        var r = gen.generate(prompt(HISTORY, 4, QuestionType.MULTIPLE_CHOICE, QuestionType.TRUE_FALSE, QuestionType.SHORT_ANSWER, QuestionType.ESSAY));
        var byType = new HashMap<String, Candidate>();
        r.candidates().forEach(c -> byType.putIfAbsent(c.type(), c));
        assertEquals(Set.of("MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "ESSAY"), byType.keySet());
        var tf = byType.get("TRUE_FALSE");
        assertEquals(List.of("Đúng", "Sai"), tf.options().stream().map(CandidateOption::content).toList());
        boolean saysTrue = Boolean.TRUE.equals(tf.options().getFirst().correct());
        assertEquals(saysTrue, tf.explanation().startsWith("Đúng."), "the key and the explanation agree");
        var fill = byType.get("SHORT_ANSWER");
        assertTrue(fill.content().contains("_____") && fill.options().isEmpty() && !fill.expectedAnswer().isBlank());
        assertTrue(fill.explanation().contains(fill.expectedAnswer()));
        var essay = byType.get("ESSAY");
        assertEquals("HARD", essay.difficulty());
        assertTrue(essay.expectedAnswer().startsWith("Gợi ý chấm"));
    }

    @Test void falseStatementsChangeTheFactAndTrueOnesKeepIt() {
        for (int i = 0; i < 6; i++) {
            var p = new Prompt("Chủ đề " + i, HISTORY, List.of(), 5, List.of(QuestionType.TRUE_FALSE), DifficultyLevel.EASY, "vi");
            for (var c : gen.generate(p).candidates()) {
                String statement = c.content().substring(c.content().indexOf('«') + 1, c.content().lastIndexOf('»'));
                boolean inMaterial = HISTORY.contains(statement);
                assertEquals(inMaterial, Boolean.TRUE.equals(c.options().getFirst().correct()), statement);
            }
        }
    }

    @Test void factsPreferNumbersThenNamesAndNumbersGetPlausibleVariants() {
        var terms = LocalQuestionGenerator.factTerms(HISTORY);
        assertTrue(terms.contains("1010") && terms.contains("1802"), terms.toString());
        var years = LocalQuestionGenerator.numberVariants("1010");
        assertTrue(years.containsAll(List.of("1000", "1020", "1009", "1011")), years.toString());
        assertTrue(LocalQuestionGenerator.numberVariants("2,5").contains("5"), "decimal comma kept");
        assertTrue(LocalQuestionGenerator.numberVariants("40%").stream().allMatch(v -> v.endsWith("%")));
        assertEquals(List.of(), LocalQuestionGenerator.numberVariants("abc"));
    }

    @Test void englishTemplatesAndHeadersFromRetrievalAreHandled() {
        String material = "[1] Wikipedia – Water: Water boils at 100 degrees Celsius at sea level under normal pressure.\n\n"
                + "[2] Wikipedia – Water: Water freezes at 0 degrees Celsius and expands by about 9 percent when it turns into ice.";
        var p = new Prompt("Water", material, List.of(), 2, List.of(QuestionType.SHORT_ANSWER), DifficultyLevel.EASY, "en");
        var r = gen.generate(p);
        assertFalse(r.candidates().isEmpty());
        assertTrue(r.candidates().getFirst().content().startsWith("Fill in the blank"));
        assertFalse(r.candidates().getFirst().content().contains("Wikipedia –"), "source labels are not part of questions");
    }

    @Test void withoutUsableMaterialItSaysWhatItNeeds() {
        var none = assertThrows(GenerationException.class, () -> gen.generate(prompt(null, 2, QuestionType.MULTIPLE_CHOICE)));
        assertTrue(none.getMessage().startsWith("Bộ sinh nội bộ cần tài liệu"));
        assertFalse(none.transientFailure());
        assertThrows(GenerationException.class, () -> gen.generate(prompt("Ngắn.", 2, QuestionType.MULTIPLE_CHOICE)));
    }

    @Test void statementGroupsComeFromDifferentSentencesAndKeepAtLeastOneTrue() {
        // Six sentences give one group of four statements; a second group would reuse sentences, so it is not made.
        var r = gen.generate(prompt(HISTORY, 2, QuestionType.TRUE_FALSE_GROUP));
        assertEquals(1, r.candidates().size());
        var group = r.candidates().get(0);
        assertEquals("TRUE_FALSE_GROUP", group.type());
        assertTrue(group.options().size() >= 3 && group.options().size() <= 4);
        assertEquals(group.options().size(), group.options().stream().map(o -> o.content()).distinct().count());
        var m = gen.generate(prompt(HISTORY, 1, QuestionType.MULTIPLE_RESPONSE));
        var multi = m.candidates().get(0);
        assertEquals("MULTIPLE_RESPONSE", multi.type());
        assertTrue(multi.options().stream().anyMatch(o -> Boolean.TRUE.equals(o.correct())));
        var drafts = AiDrafts.build(List.of(group, multi), 2, DifficultyLevel.MEDIUM, List.of());
        assertTrue(drafts.items().stream().noneMatch(d -> d.flags().contains(Flag.MULTIPLE_ANSWERS) || d.flags().contains(Flag.MISSING_CORRECT_ANSWER)));
    }

    @Test void clozeAndMatchingDraftsAreValidQuestions() {
        var fill = gen.generate(prompt(HISTORY, 1, QuestionType.FILL_BLANK)).candidates().get(0);
        assertEquals("FILL_BLANK", fill.type());
        assertEquals(fill.options().size(), com.datn.questionbank.service.StructuredTypes.blanks(fill.content()));
        var match = gen.generate(prompt(HISTORY, 1, QuestionType.MATCHING)).candidates().get(0);
        assertEquals("MATCHING", match.type());
        assertTrue(match.options().size() >= 3);
        assertTrue(match.options().stream().allMatch(o -> com.datn.questionbank.service.StructuredTypes.pair(o.content()) != null));
        var drafts = AiDrafts.build(List.of(fill, match), 2, DifficultyLevel.MEDIUM, List.of());
        for (var d : drafts.items()) {
            assertTrue(d.options().stream().allMatch(com.datn.questionbank.service.ImportParser.Option::correct));
            var q = com.datn.questionbank.entity.Question.builder().category(new com.datn.questionbank.entity.Category()).content(d.content())
                    .questionType(d.type()).difficultyLevel(DifficultyLevel.MEDIUM).build();
            for (var o : d.options()) q.getOptions().add(com.datn.questionbank.entity.QuestionOption.builder().question(q).content(o.content()).correct(o.correct()).displayOrder(o.displayOrder()).build());
            com.datn.questionbank.service.QuestionValidation.validate(q);
        }
    }

    /** Found by the end-to-end suite: a short paragraph with only multiple choice requested gave nothing at all. */
    @org.junit.jupiter.api.Test void shortMaterialStillGivesMultipleChoiceWithDistinctOptions() {
        String este = "Este là hợp chất hữu cơ tạo thành khi thay nhóm OH của axit cacboxylic bằng nhóm OR. Chất béo là trieste của glixerol với axit béo. "
                + "Phản ứng xà phòng hoá chất béo trong dung dịch kiềm tạo ra glixerol và muối của axit béo (xà phòng). Este có mùi thơm, nhẹ hơn nước và ít tan trong nước.";
        var r = new LocalQuestionGenerator().generate(new Prompt("Este và chất béo", este, List.of(), 2, List.of(QuestionType.MULTIPLE_CHOICE), DifficultyLevel.EASY, "vi"));
        org.junit.jupiter.api.Assertions.assertFalse(r.candidates().isEmpty());
        for (var q : r.candidates()) {
            org.junit.jupiter.api.Assertions.assertEquals("MULTIPLE_CHOICE", q.type());
            org.junit.jupiter.api.Assertions.assertEquals(4, q.options().size());
            org.junit.jupiter.api.Assertions.assertEquals(1, q.options().stream().filter(o -> Boolean.TRUE.equals(o.correct())).count());
            var texts = q.options().stream().map(o -> LocalQuestionGenerator.fold(o.content())).distinct().count();
            org.junit.jupiter.api.Assertions.assertEquals(4, texts, "options are distinct");
            var key = q.options().stream().filter(o -> Boolean.TRUE.equals(o.correct())).findFirst().orElseThrow().content();
            for (var o : q.options()) if (!Boolean.TRUE.equals(o.correct()))
                org.junit.jupiter.api.Assertions.assertFalse(q.content().toLowerCase().contains(o.content().toLowerCase()), "a distractor already in the stem would be obviously wrong: " + o.content() + " / key " + key);
        }
    }

    @org.junit.jupiter.api.Test void tooThinMaterialExplainsWhatToDo() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(AiQuestionGenerator.GenerationException.class, () -> new LocalQuestionGenerator().generate(
                new Prompt("x", "Mèo kêu meo meo khi đói bụng vào buổi sáng sớm.", List.of(), 1, List.of(QuestionType.MULTIPLE_CHOICE), DifficultyLevel.EASY, "vi")));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("Tài liệu"), e.getMessage());
    }
}
