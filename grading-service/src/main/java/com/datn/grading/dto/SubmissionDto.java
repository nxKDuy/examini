package com.datn.grading.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SubmissionDto {
    private SubmissionDto() {}

    public record SummaryResponse(UUID id,UUID examId,String examTitle,UUID studentId,String status,
                                  Float score,Float totalScore,Instant submittedAt,Instant gradedAt,
                                  Integer durationSeconds,String feedback,long resultRevision,Long releasedRevision) {}

    public record DetailResponse(UUID id,UUID examId,String examTitle,UUID studentId,String status,
                                 Float score,Float totalScore,Instant submittedAt,Instant gradedAt,
                                 Integer durationSeconds,String feedback,long resultRevision,Long releasedRevision,
                                 List<ItemResponse> items) {}

    public record ItemResponse(UUID questionId,long questionVersion,int position,String questionContent,String questionType,
                               Float pointValue,String answerContent,String selectedOptionContent,Float autoScore,
                               Float manualScore,Float awardedPoints,boolean needsReview,String feedback,
                               UUID manualGradedBy,Instant manualGradedAt) {}

    public record ItemGrade(UUID questionId,Float score,String feedback) {}
    public record SaveDraftRequest(long expectedRevision,String feedback,List<ItemGrade> items) {}
    public record RevisionRequest(long expectedRevision) {}
    public record RegradeRequest(long expectedRevision,String publicReason,String privateAuditNote) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentResult(UUID sessionId,String title,String status,Long resultRevision,Instant releasedAt,
                                Float score,Float totalScore,String feedback,List<StudentItem> questions,
                                List<PublishedRevision> history) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentItem(UUID questionId,long questionVersion,int position,String questionContent,String questionType,
                              Float pointValue,String answer,Float awardedPoints,String feedback,List<String> correctAnswers) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublishedRevision(long resultRevision,Instant releasedAt,Float score,Float totalScore,String publicReason) {}
}
