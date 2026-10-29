package com.datn.questionbank.service.ai;

import com.datn.questionbank.entity.Question.DifficultyLevel;
import com.datn.questionbank.entity.Question.QuestionType;
import java.util.List;

/**
 * Provider port for AI question generation. Implementations return raw candidates only; they never
 * persist anything. {@link AiDrafts} validates and flags every candidate before a teacher reviews it.
 */
public interface AiQuestionGenerator {
    /** False when no provider is configured; callers must reject generation instead of failing later. */
    boolean available();
    /** Provider/model label stored as provenance, e.g. {@code gemini/gemini-2.5-flash}. */
    String model();
    Result generate(Prompt prompt);

    /**
     * @param topic       teacher's topic or learning objective
     * @param material    optional knowledge text supplied by the teacher (untrusted input)
     * @param examples    existing questions of the same category, used for style and to avoid duplicates
     */
    record Prompt(String topic, String material, List<String> examples, int count, List<QuestionType> types,
                  DifficultyLevel difficulty, String language) {}
    record Candidate(String type, String content, List<CandidateOption> options, String expectedAnswer,
                     String explanation, String difficulty, String raw) {}
    record CandidateOption(String content, Boolean correct) {}
    record Result(List<Candidate> candidates, List<String> warnings) {}

    /** Provider failure with a message safe to show the teacher. */
    class GenerationException extends RuntimeException {
        public GenerationException(String message) { super(message); }
        public GenerationException(String message, Throwable cause) { super(message, cause); }
    }
}
