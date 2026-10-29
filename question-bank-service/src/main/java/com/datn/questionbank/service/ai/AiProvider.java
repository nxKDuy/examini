package com.datn.questionbank.service.ai;

/** One source of question candidates (Gemini, a self-hosted model, the local rule-based generator). */
public interface AiProvider {
    /** Stable key used in configuration ({@code question-bank.ai.providers}), e.g. {@code gemini}. */
    String name();
    boolean available();
    String model();
    /** @throws AiQuestionGenerator.GenerationException with a teacher-safe message */
    AiQuestionGenerator.Result generate(AiQuestionGenerator.Prompt prompt);
}
