package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import java.time.Duration;
import java.util.List;

/**
 * Question generation as the import service sees it. The only implementation is {@link ResilientQuestionGenerator},
 * which tries the configured {@link AiProvider}s in order (cloud LLM, self-hosted LLM, local rule-based generator) so
 * a rate-limited or offline provider never takes the feature down. Implementations return raw candidates only;
 * {@link AiDrafts} validates and flags every candidate before a teacher reviews it.
 */
public interface AiQuestionGenerator {
    /** False only when no provider at all can run. */
    boolean available();
    /** Provider/model label of the first usable provider, e.g. {@code gemini/gemini-2.5-flash}. */
    String model();
    Result generate(Prompt prompt);
    /** Configured providers in fallback order, for the status endpoint. */
    default List<ProviderStatus> providers() { return List.of(); }

    /**
     * @param topic       teacher's topic or learning objective
     * @param material    knowledge text: the teacher's material and/or retrieved passages (untrusted input)
     * @param examples    existing questions of the same category, used for style and to avoid duplicates
     */
    record Prompt(String topic, String material, List<String> examples, int count, List<QuestionType> types,
                  DifficultyLevel difficulty, String language) {}
    record Candidate(String type, String content, List<CandidateOption> options, String expectedAnswer,
                     String explanation, String difficulty, String raw) {}
    record CandidateOption(String content, Boolean correct) {}
    /** @param model the provider/model that actually produced the candidates */
    record Result(List<Candidate> candidates, List<String> warnings, String model) {
        public Result(List<Candidate> candidates, List<String> warnings) { this(candidates, warnings, null); }
        public Result withModel(String m) { return new Result(candidates, warnings, m); }
    }
    record ProviderStatus(String name, String model, boolean available, boolean coolingDown) {}

    /**
     * Provider failure with a message safe to show the teacher. {@code transientFailure} (rate limit, timeout, 5xx)
     * means the same provider may work later; {@code retryAfter} is the provider's hint, if any.
     */
    class GenerationException extends RuntimeException {
        private final boolean transientFailure;
        private final Duration retryAfter;
        public GenerationException(String message) { this(message, false, null, null); }
        public GenerationException(String message, Throwable cause) { this(message, false, null, cause); }
        public GenerationException(String message, boolean transientFailure, Duration retryAfter, Throwable cause) {
            super(message, cause); this.transientFailure = transientFailure; this.retryAfter = retryAfter;
        }
        public boolean transientFailure() { return transientFailure; }
        public Duration retryAfter() { return retryAfter; }
    }
}
