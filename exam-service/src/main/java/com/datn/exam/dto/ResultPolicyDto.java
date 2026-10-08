package com.datn.exam.dto;

import com.datn.exam.entity.Exam;

public final class ResultPolicyDto {
    private ResultPolicyDto() {}

    public record Policy(boolean autoRelease,boolean showScore,boolean showQuestionBreakdown,
                         boolean showFeedback,boolean showCorrectAnswers,boolean showExplanation) {
        public static Policy from(Exam exam) {
            return new Policy(exam.isResultAutoRelease(),exam.isResultShowScore(),exam.isResultShowQuestionBreakdown(),
                    exam.isResultShowFeedback(),exam.isResultShowCorrectAnswers(),exam.isResultShowExplanation());
        }
    }
    public record Internal(String examStatus,Policy policy) {}
}
