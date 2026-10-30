package com.datn.questionbank.service.ai;

import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tries providers in the configured order (default gemini → ollama → local). A transient failure (rate limit,
 * timeout, 5xx) is retried once after a short pause, then the provider is put on cooldown so the next requests skip
 * it; any failure moves on to the next provider. The local rule-based generator needs no network, so generation keeps
 * working when every LLM is down or out of quota.
 */
@Component
public class ResilientQuestionGenerator implements AiQuestionGenerator {
    private static final Logger log = LoggerFactory.getLogger(ResilientQuestionGenerator.class);
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60), MAX_COOLDOWN = Duration.ofMinutes(15), MAX_PAUSE = Duration.ofSeconds(5);
    private static final Map<String, String> LABEL = Map.of("gemini", "Gemini", "openai", "AI tương thích OpenAI", "ollama", "Mô hình tự chạy", "local", "Bộ sinh nội bộ");

    private final List<AiProvider> chain;
    private final Map<String, Instant> coolUntil = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Sleeper sleeper;

    interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    @org.springframework.beans.factory.annotation.Autowired
    public ResilientQuestionGenerator(List<AiProvider> providers, @Value("${question-bank.ai.providers:gemini,openai,ollama,local}") String order) {
        this(providers, order, Clock.systemUTC(), d -> Thread.sleep(d.toMillis()));
    }
    ResilientQuestionGenerator(List<AiProvider> providers, String order, Clock clock, Sleeper sleeper) {
        var byName = new HashMap<String, AiProvider>();
        providers.forEach(p -> byName.put(p.name(), p));
        var list = new ArrayList<AiProvider>();
        for (String n : order.split(",")) { var p = byName.get(n.strip()); if (p != null && !list.contains(p)) list.add(p); }
        this.chain = List.copyOf(list);
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Override public boolean available() { return chain.stream().anyMatch(AiProvider::available); }
    @Override public String model() { return chain.stream().filter(p -> p.available() && !cooling(p)).findFirst().or(() -> chain.stream().filter(AiProvider::available).findFirst()).map(AiProvider::model).orElse(null); }
    @Override public List<ProviderStatus> providers() { return chain.stream().map(p -> new ProviderStatus(p.name(), p.model(), p.available(), cooling(p))).toList(); }

    private boolean cooling(AiProvider p) { var until = coolUntil.get(p.name()); return until != null && clock.instant().isBefore(until); }

    /**
     * A JSON completion from the first usable language model (cooling providers skipped), for checks after
     * generation. Empty when no model can answer: the caller then simply skips the check.
     */
    /** Checks run in the background, so a short rate-limit wait (free tiers count tokens per minute) is acceptable. */
    static final Duration MAX_CHECK_WAIT = Duration.ofSeconds(30);

    @Override
    public java.util.Optional<String> completeJson(String system, String user) {
        for (AiProvider p : chain) {
            if (!p.available() || !p.llm() || cooling(p)) continue;
            for (int attempt = 0; attempt < 2; attempt++) {
                try { return java.util.Optional.of(p.completeJson(system, user)); }
                catch (GenerationException e) {
                    log.warn("AI provider {} could not run a check: {}", p.name(), e.getMessage());
                    if (!e.transientFailure()) break;
                    Duration wait = e.retryAfter() == null ? Duration.ofSeconds(5) : e.retryAfter();
                    if (attempt == 0 && wait.compareTo(MAX_CHECK_WAIT) <= 0) {
                        try { sleeper.sleep(wait.plusMillis(500)); continue; }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return java.util.Optional.empty(); }
                    }
                    coolUntil.put(p.name(), clock.instant().plus(e.retryAfter() == null ? DEFAULT_COOLDOWN : e.retryAfter().compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : e.retryAfter()));
                    break;
                } catch (RuntimeException e) { log.warn("AI provider {} crashed during a check", p.name(), e); break; }
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public Result generate(Prompt prompt) {
        var notes = new ArrayList<String>();
        GenerationException last = null;
        for (AiProvider p : chain) {
            if (!p.available()) continue;
            if (cooling(p)) { notes.add(LABEL.getOrDefault(p.name(), p.name()) + " đang tạm nghỉ do bị giới hạn; đã bỏ qua"); continue; }
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    var r = p.generate(prompt);
                    coolUntil.remove(p.name());
                    if (r.candidates().isEmpty()) throw new GenerationException("AI không trả về câu hỏi nào");
                    var warnings = new ArrayList<>(notes);
                    if (!notes.isEmpty()) warnings.add("Đã dùng " + LABEL.getOrDefault(p.name(), p.name()) + " thay thế");
                    warnings.addAll(r.warnings());
                    return new Result(r.candidates(), warnings, r.model() == null ? p.model() : r.model());
                } catch (GenerationException e) {
                    last = e;
                    log.warn("AI provider {} failed (attempt {}): {}", p.name(), attempt + 1, e.getMessage());
                    if (!e.transientFailure()) break;
                    Duration hint = e.retryAfter() == null ? Duration.ofSeconds(1) : e.retryAfter();
                    if (attempt == 0 && hint.compareTo(MAX_PAUSE) <= 0) {
                        try { sleeper.sleep(hint); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }
                    Duration cool = e.retryAfter() == null ? DEFAULT_COOLDOWN : e.retryAfter().compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : e.retryAfter();
                    coolUntil.put(p.name(), clock.instant().plus(cool));
                    break;
                } catch (RuntimeException e) {
                    last = new GenerationException("Không tạo được câu hỏi", e);
                    log.warn("AI provider {} crashed", p.name(), e);
                    break;
                }
            }
            notes.add(LABEL.getOrDefault(p.name(), p.name()) + ": " + (last == null ? "lỗi" : last.getMessage()));
        }
        throw last != null ? new GenerationException(last.getMessage() + (notes.size() > 1 ? " (đã thử: " + String.join("; ", notes) + ")" : ""), last)
                : new GenerationException("Chưa cấu hình nguồn sinh câu hỏi nào");
    }
}
