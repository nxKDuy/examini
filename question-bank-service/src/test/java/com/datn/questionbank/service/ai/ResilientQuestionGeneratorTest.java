package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class ResilientQuestionGeneratorTest {
    static final Prompt PROMPT = new Prompt("t", "m", List.of(), 1, List.of(QuestionType.ESSAY), DifficultyLevel.EASY, "vi");
    static Result ok(String model) { return new Result(List.of(new Candidate("ESSAY", "Q?", List.of(), null, null, null, null)), List.of(), model); }

    static class Fake implements AiProvider {
        final String name; boolean available = true; int calls; final Deque<Supplier<Result>> script = new ArrayDeque<>();
        Fake(String name) { this.name = name; }
        public String name() { return name; }
        public boolean available() { return available; }
        public String model() { return name + "/m"; }
        public Result generate(Prompt p) { calls++; return script.isEmpty() ? ok(model()) : script.poll().get(); }
        Fake fail(GenerationException e) { script.add(() -> { throw e; }); return this; }
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T08:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }
    static GenerationException rateLimited(Duration after) { return new GenerationException("Gemini đang bị giới hạn lượt gọi", true, after, null); }

    @Test void rateLimitedProviderIsSkippedDuringCooldownThenTriedAgain() {
        var gemini = new Fake("gemini").fail(rateLimited(Duration.ofSeconds(30)));
        var local = new Fake("local");
        var clock = new MutableClock(); var pauses = new ArrayList<Duration>();
        var g = new ResilientQuestionGenerator(List.of(local, gemini), "gemini,ollama,local", clock, pauses::add);

        var first = g.generate(PROMPT);
        assertEquals("local/m", first.model());
        assertTrue(pauses.isEmpty(), "a long Retry-After is not waited for inline");
        assertTrue(first.warnings().get(0).startsWith("Gemini: Gemini đang bị giới hạn"));
        assertEquals("Đã dùng Bộ sinh nội bộ thay thế", first.warnings().get(1));
        assertTrue(g.providers().stream().filter(p -> p.name().equals("gemini")).findFirst().orElseThrow().coolingDown());

        var second = g.generate(PROMPT);
        assertEquals(1, gemini.calls, "cooling provider is not called");
        assertEquals("Gemini đang tạm nghỉ do bị giới hạn; đã bỏ qua", second.warnings().getFirst());
        assertEquals("local/m", g.model());

        clock.now = clock.now.plusSeconds(31);
        assertEquals("gemini/m", g.generate(PROMPT).model());
        assertEquals(2, gemini.calls);
    }

    @Test void shortTransientFailureIsRetriedOnceInPlace() {
        var gemini = new Fake("gemini").fail(rateLimited(Duration.ofSeconds(2)));
        var pauses = new ArrayList<Duration>();
        var g = new ResilientQuestionGenerator(List.of(gemini, new Fake("local")), "gemini,local", new MutableClock(), pauses::add);
        var r = g.generate(PROMPT);
        assertEquals("gemini/m", r.model());
        assertEquals(List.of(Duration.ofSeconds(2)), pauses);
        assertTrue(r.warnings().isEmpty());
    }

    @Test void permanentFailuresMoveOnWithoutCooldownAndUnavailableProvidersAreIgnored() {
        var gemini = new Fake("gemini").fail(new GenerationException("Khóa Gemini không hợp lệ hoặc không có quyền"));
        var ollama = new Fake("ollama"); ollama.available = false;
        var local = new Fake("local");
        var g = new ResilientQuestionGenerator(List.of(gemini, ollama, local), "gemini,ollama,local", new MutableClock(), d -> fail("no pause"));
        assertEquals("local/m", g.generate(PROMPT).model());
        assertEquals(0, ollama.calls);
        assertFalse(g.providers().getFirst().coolingDown());
        assertEquals(List.of("gemini", "ollama", "local"), g.providers().stream().map(ProviderStatus::name).toList());
    }

    @Test void emptyOutputCountsAsFailureAndAllFailingReportsEveryAttempt() {
        var gemini = new Fake("gemini"); gemini.script.add(() -> new Result(List.of(), List.of(), "gemini/m"));
        var local = new Fake("local").fail(new GenerationException("Bộ sinh nội bộ cần tài liệu kiến thức"));
        var g = new ResilientQuestionGenerator(List.of(gemini, local), "gemini,local", new MutableClock(), d -> {});
        var e = assertThrows(GenerationException.class, () -> g.generate(PROMPT));
        assertTrue(e.getMessage().startsWith("Bộ sinh nội bộ cần tài liệu kiến thức (đã thử: Gemini: AI không trả về câu hỏi nào"), e.getMessage());
    }

    @Test void orderComesFromConfigurationAndNothingConfiguredIsUnavailable() {
        var gemini = new Fake("gemini"); var local = new Fake("local");
        assertEquals("local/m", new ResilientQuestionGenerator(List.of(gemini, local), "local,gemini", new MutableClock(), d -> {}).generate(PROMPT).model());
        var none = new ResilientQuestionGenerator(List.of(gemini), "ollama", new MutableClock(), d -> {});
        assertFalse(none.available());
        assertEquals("Chưa cấu hình nguồn sinh câu hỏi nào", assertThrows(GenerationException.class, () -> none.generate(PROMPT)).getMessage());
    }

    @Test void checksUseTheFirstWorkingLanguageModelAndNeverTheRuleGenerator() {
        var gemini = new Fake("gemini") { public String completeJson(String s, String u) { throw rateLimited(Duration.ofSeconds(20)); } };
        var openai = new Fake("openai") { public String completeJson(String s, String u) { return "{\"answers\":[]}"; } };
        var local = new Fake("local") { public boolean llm() { return false; } public String completeJson(String s, String u) { throw new AssertionError(); } };
        var clock = new MutableClock();
        var g = new ResilientQuestionGenerator(List.of(local, openai, gemini), "gemini,openai,ollama,local", clock, d -> {});
        assertEquals(Optional.of("{\"answers\":[]}"), g.completeJson("s", "u"));
        assertTrue(g.providers().stream().filter(p -> p.name().equals("gemini")).findFirst().orElseThrow().coolingDown());
        openai.available = false;
        assertEquals(Optional.empty(), g.completeJson("s", "u"), "only the rule generator is left: no check");
    }

    @Test void checksWaitOutAShortRateLimitOnce() {
        var hits = new int[1];
        var groq = new Fake("openai") { public String completeJson(String s, String u) {
            if (hits[0]++ == 0) throw rateLimited(Duration.ofSeconds(7)); return "{}"; } };
        var pauses = new ArrayList<Duration>();
        var g = new ResilientQuestionGenerator(List.of(groq), "openai", new MutableClock(), pauses::add);
        assertEquals(Optional.of("{}"), g.completeJson("s", "u"));
        assertEquals(List.of(Duration.ofMillis(7500)), pauses);
        var slow = new Fake("openai") { public String completeJson(String s, String u) { throw rateLimited(Duration.ofMinutes(5)); } };
        var g2 = new ResilientQuestionGenerator(List.of(slow), "openai", new MutableClock(), d -> { throw new AssertionError("no long waits"); });
        assertEquals(Optional.empty(), g2.completeJson("s", "u"));
    }
}
