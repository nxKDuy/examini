package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ImportParser.Flag;
import com.datn.questionbank.service.ai.AiQuestionGenerator.Candidate;
import com.datn.questionbank.service.ai.AiQuestionGenerator.CandidateOption;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AiQualityTest {
    static Set<Flag> mc(String stem, String... opts) {
        // first option is the key
        var correct = new Boolean[opts.length];
        Arrays.fill(correct, false);
        correct[0] = true;
        return AiQuality.check(stem, QuestionType.MULTIPLE_CHOICE, List.of(opts), Arrays.asList(correct), List.of(), List.of());
    }

    @Test void cleanQuestionGetsNoFlags() {
        assertEquals(Set.of(), mc("Thủ đô của Việt Nam là thành phố nào?", "Hà Nội", "Huế", "Đà Nẵng", "Cần Thơ"));
    }

    @Test void referencesToAnUnseenSourceAreFlagged() {
        assertTrue(mc("Theo đoạn văn trên, nguyên nhân chính là gì?", "Mưa lớn", "Nắng nóng", "Gió mùa", "Động đất").contains(Flag.SOURCE_REFERENCE));
        assertTrue(mc("Trong tài liệu, tác giả cho rằng điều gì?", "A1 x", "B2 y", "C3 z", "D4 w").contains(Flag.SOURCE_REFERENCE));
        assertTrue(mc("According to the passage, why do cities plant trees?", "To cool the air", "To sell wood", "To stop cars", "To build roads").contains(Flag.SOURCE_REFERENCE));
        // the stem carries its own text: the reference points at something the student sees
        String own = "Đọc đoạn văn sau: " + "Nước là hợp chất của hiđro và oxi. ".repeat(14) + "Theo đoạn văn trên, nước gồm những nguyên tố nào?";
        assertFalse(mc(own, "Hiđro và oxi", "Cacbon và oxi", "Nitơ và hiđro", "Lưu huỳnh").contains(Flag.SOURCE_REFERENCE));
        assertFalse(mc("Văn bản hành chính dùng phong cách ngôn ngữ nào?", "Hành chính", "Nghệ thuật", "Báo chí", "Chính luận").contains(Flag.SOURCE_REFERENCE));
    }

    @Test void catchAllAndLookAlikeOptions() {
        assertTrue(mc("Kim loại nào dẫn điện tốt?", "Bạc", "Đồng", "Nhôm", "Tất cả các đáp án trên").contains(Flag.CATCH_ALL_OPTION));
        assertTrue(mc("Chọn đáp án đúng", "Cả A, B và C", "x", "y", "z").contains(Flag.CATCH_ALL_OPTION));
        assertTrue(mc("Which is correct?", "All of the above", "a1", "b2", "c3").contains(Flag.CATCH_ALL_OPTION));
        assertTrue(mc("Quá trình nào tạo ra oxi?", "Quang hợp ở cây xanh", "Quang hợp ở cây xanhh", "Hô hấp", "Lên men").contains(Flag.WEAK_DISTRACTORS));
        assertTrue(mc("Thành phố nào là thủ đô?", "Hà Nội", "Thành phố Hà Nội cũ", "Huế", "Vinh").contains(Flag.WEAK_DISTRACTORS));
        assertFalse(mc("Nghiệm của 2x = 4?", "x = 2", "x = 4", "x = 1", "x = 8").contains(Flag.WEAK_DISTRACTORS));
        assertFalse(mc("Hàm số nào?", "y = 2x³ − 3x² + 1", "y = 2x³ + 3x² + 1", "y = x³", "y = x").contains(Flag.WEAK_DISTRACTORS));
        assertFalse(mc("Chọn phương án", "Lựa chọn A", "Lựa chọn B", "Lựa chọn C", "Lựa chọn D").contains(Flag.WEAK_DISTRACTORS));
        // signs and accents make options different, not duplicates
        var math = AiDrafts.build(List.of(new Candidate("MULTIPLE_CHOICE", "Nghiệm của x² = 1?", List.of(new CandidateOption("x = 1", true),
                new CandidateOption("x = −1", false), new CandidateOption("Huế", false), new CandidateOption("Hue", false)), null, null, null, null)), 1, null, List.of());
        assertFalse(math.items().getFirst().flags().contains(Flag.DUPLICATE_OPTION));
    }

    @Test void stemThatGivesTheAnswerAway() {
        assertTrue(mc("Quá trình quang hợp ở cây xanh tạo ra khí gì trong quang hợp ở cây xanh?", "Quang hợp ở cây xanh", "Oxi hóa", "Khử", "Điện phân")
                .contains(Flag.ANSWER_IN_STEM));
        assertFalse(mc("So sánh Hà Nội và Huế: thành phố nào là thủ đô?", "Hà Nội", "Huế", "Vinh", "Đà Lạt").contains(Flag.ANSWER_IN_STEM),
                "a distractor is named in the stem too, so it is a real choice");
        assertFalse(mc("Tính 12 + 12", "24", "12", "36", "48").contains(Flag.ANSWER_IN_STEM));
    }

    @Test void nearDuplicatesAgainstBankAndBatch() {
        var d = AiDrafts.build(List.of(
                cand("Phương trình bậc hai ax² + bx + c = 0 có bao nhiêu nghiệm khi Δ > 0?"),
                cand("Phương trình bậc hai ax² + bx + c = 0 có bao nhiêu nghiệm khi Δ > 0 ?!"),
                cand("Đạo hàm của hàm số y = sin x là gì trong chương trình lớp 11?")), 3, DifficultyLevel.EASY,
                List.of("Đạo hàm của hàm số y = sin x là gì trong chương trình lớp 11 ?"));
        assertFalse(d.items().get(0).flags().contains(Flag.POSSIBLE_DUPLICATE));
        assertTrue(d.items().get(1).flags().contains(Flag.POSSIBLE_DUPLICATE));
        assertTrue(d.items().get(2).flags().contains(Flag.POSSIBLE_DUPLICATE));
        assertTrue(AiQuality.dice("abcdef", "abcdef") == 1 && AiQuality.dice("ab", "cd") == 0);
        assertEquals(0.8, AiQuality.similarity("abcde", "abcdx"), 1e-9);
    }

    @Test void flagsLowerConfidence() {
        var clean = AiDrafts.build(List.of(cand("Thủ đô của Việt Nam là thành phố nào?")), 1, null, List.of()).items().getFirst();
        var weak = AiDrafts.build(List.of(new Candidate("MULTIPLE_CHOICE", "Theo đoạn văn trên, chọn ý đúng", List.of(
                new CandidateOption("Tất cả các đáp án trên", true), new CandidateOption("Sai rồi", false)), null, null, null, null)), 1, null, List.of()).items().getFirst();
        assertTrue(weak.confidence() < clean.confidence());
    }

    static Candidate cand(String stem) {
        return new Candidate("MULTIPLE_CHOICE", stem, List.of(new CandidateOption("Một", true), new CandidateOption("Hai", false),
                new CandidateOption("Ba", false), new CandidateOption("Bốn", false)), null, null, "EASY", null);
    }
}
