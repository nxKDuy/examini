package com.datn.questionbank.service.ai;

/** One source of question candidates (Gemini, a self-hosted model, the local rule-based generator). */
public interface AiProvider {
    /** Stable key used in configuration ({@code question-bank.ai.providers}), e.g. {@code gemini}. */
    String name();
    boolean available();
    String model();
    /** @throws AiQuestionGenerator.GenerationException with a teacher-safe message */
    AiQuestionGenerator.Result generate(AiQuestionGenerator.Prompt prompt);
    /** True when the provider is a language model that can answer {@link #completeJson} (the local generator cannot). */
    default boolean llm() { return true; }
    /**
     * One deterministic JSON-only completion for auxiliary tasks such as re-solving generated questions.
     * @throws AiQuestionGenerator.GenerationException when unsupported or failing
     */
    default String completeJson(String system, String user) {
        throw new AiQuestionGenerator.GenerationException(name() + " không hỗ trợ kiểm tra lại");
    }
}
