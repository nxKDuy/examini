package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ImportParser.Flag;
import com.datn.questionbank.service.ImportParser.Option;
import com.datn.questionbank.service.ImportParser.Parsed;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiKeyCheckTest {
    static Parsed item(QuestionType type, String stem, boolean... correct) {
        var opts = new ArrayList<Option>();
        for (int i = 0; i < correct.length; i++) opts.add(new Option("Phương án " + (char) ('A' + i), correct[i], (short) i));
        return new Parsed(stem, type, DifficultyLevel.MEDIUM, opts, null, null, null, SourceMethod.AI, 0.7, stem, List.of(Flag.AI_GENERATED), true);
    }
    static final List<Parsed> ITEMS = List.of(
            item(QuestionType.MULTIPLE_CHOICE, "Câu một?", false, true, false, false),
            new Parsed("Viết đoạn văn", QuestionType.ESSAY, DifficultyLevel.MEDIUM, List.of(), "Gợi ý", null, null, SourceMethod.AI, 0.7, "x", List.of(Flag.AI_GENERATED), true),
            item(QuestionType.MULTIPLE_RESPONSE, "Chọn tất cả?", true, false, true, false),
            item(QuestionType.TRUE_FALSE_GROUP, "Xét các ý", true, false, true, true));

    @Test void disagreementsAreFlaggedAndPromptHasNoKeys() {
        var seen = new AtomicReference<String>();
        var check = new AiKeyCheck((system, user) -> { seen.set(user);
            return Optional.of("{\"answers\":[{\"n\":1,\"choose\":[\"B\"]},{\"n\":2,\"choose\":[\"A\"]},{\"n\":3,\"truth\":[true,false,true,true]}]}"); }, true);
        var out = check.verify(new AiDrafts.Drafts(ITEMS, List.of()), "Tài liệu nguồn", "vi");
        assertFalse(out.items().get(0).flags().contains(Flag.KEY_DISPUTED));
        assertTrue(out.items().get(2).flags().contains(Flag.KEY_DISPUTED), "picked only A, key is A+C");
        assertEquals(0.5, out.items().get(2).confidence(), 1e-9);
        assertFalse(out.items().get(3).flags().contains(Flag.KEY_DISPUTED));
        assertSame(ITEMS.get(1), out.items().get(1), "essay untouched");
        assertTrue(out.warnings().getLast().contains("1 câu có đáp án chưa thống nhất"));
        assertTrue(seen.get().contains("Tài liệu nguồn") && seen.get().contains("[MULTI]") && seen.get().contains("[STATEMENTS]"));
        assertFalse(seen.get().toLowerCase().contains("correct"), "the key is never sent");
    }

    @Test void unavailableOrBrokenModelLeavesDraftsAsIs() {
        var drafts = new AiDrafts.Drafts(ITEMS, List.of());
        var none = new AiKeyCheck((s, u) -> Optional.empty(), true).verify(drafts, null, "vi");
        assertEquals(ITEMS, none.items());
        assertTrue(none.warnings().getFirst().startsWith("Chưa kiểm tra chéo"));
        var broken = new AiKeyCheck((s, u) -> Optional.of("không phải JSON"), true).verify(drafts, null, "vi");
        assertEquals(ITEMS, broken.items());
        var off = new AiKeyCheck((s, u) -> { throw new AssertionError("must not call"); }, false).verify(drafts, null, "vi");
        assertSame(drafts, off);
        // out-of-range letters or wrong lengths are ignored, not counted as disagreement
        var odd = new AiKeyCheck((s, u) -> Optional.of("[{\"n\":1,\"choose\":[\"Z\"]},{\"n\":3,\"truth\":[true]}]"), true).verify(drafts, null, "vi");
        assertTrue(odd.items().stream().noneMatch(i -> i.flags().contains(Flag.KEY_DISPUTED)));
    }

    @Test void agreementRules() throws Exception {
        var mc = ITEMS.getFirst();
        assertEquals(Boolean.TRUE, AiKeyCheck.agrees(mc, AiJson.JSON.readTree("{\"choose\":[\"b\"]}")));
        assertEquals(Boolean.FALSE, AiKeyCheck.agrees(mc, AiJson.JSON.readTree("{\"choose\":[\"A\"]}")));
        assertNull(AiKeyCheck.agrees(mc, AiJson.JSON.readTree("{\"choose\":[]}")));
        assertEquals(Boolean.FALSE, AiKeyCheck.agrees(ITEMS.get(3), AiJson.JSON.readTree("{\"truth\":[true,true,true,true]}")));
    }
}
