package com.datn.grading.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SubmissionDto {
    private SubmissionDto() {}

    public record SummaryResponse(UUID id,UUID examId,String examTitle,UUID studentId,String studentDisplayName,int attemptNumber,String status,
                                  Float score,Float totalScore,Float publishedScore,int pendingManualItemCount,
                                  Instant submittedAt,Instant gradedAt,Integer durationSeconds,String feedback,
                                  long resultRevision,Long publishedRevision,boolean invalidated,Instant invalidatedAt,String invalidationReason) {}

    public record DetailResponse(UUID id,UUID examId,String examTitle,UUID studentId,String studentDisplayName,int attemptNumber,String status,
                                 Float score,Float totalScore,Float publishedScore,int pendingManualItemCount,
                                 Instant submittedAt,Instant gradedAt,Integer durationSeconds,String feedback,
                                 long resultRevision,Long publishedRevision,
                                 List<ItemResponse> items,boolean invalidated,Instant invalidatedAt,String invalidationReason,UUID sessionId) {
        public DetailResponse(UUID id,UUID examId,String examTitle,UUID studentId,String studentDisplayName,int attemptNumber,String status,
                              Float score,Float totalScore,Float publishedScore,int pendingManualItemCount,Instant submittedAt,Instant gradedAt,
                              Integer durationSeconds,String feedback,long resultRevision,Long publishedRevision,List<ItemResponse> items,
                              boolean invalidated,Instant invalidatedAt,String invalidationReason){
            this(id,examId,examTitle,studentId,studentDisplayName,attemptNumber,status,score,totalScore,publishedScore,pendingManualItemCount,submittedAt,
                 gradedAt,durationSeconds,feedback,resultRevision,publishedRevision,items,invalidated,invalidatedAt,invalidationReason,null);
        }
    }

    public record Overview(long totalSubmissions,long needsReview,long pending,long graded,long released,long pendingManualItems,long invalidated) {}

    public record ItemResponse(UUID questionId,long questionVersion,int position,String questionContent,String questionType,
                               Float pointValue,String answerContent,String selectedOptionContent,Float autoScore,
                               Float manualScore,Float awardedPoints,boolean needsReview,String feedback,
                               UUID manualGradedBy,Instant manualGradedAt) {}

    public record ItemGrade(UUID questionId,Float score,String feedback,String overrideReason) {
        public ItemGrade(UUID questionId,Float score,String feedback){this(questionId,score,feedback,null);}
    }
    public record SaveDraftRequest(long expectedRevision,String feedback,List<ItemGrade> items) {}
    public record RevisionRequest(long expectedRevision) {}
    public record RegradeRequest(long expectedRevision,String publicReason,String privateAuditNote) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentResult(UUID sessionId,UUID examId,String title,int attemptNumber,String status,Long resultRevision,
                                Instant submittedAt,Instant gradedAt,Instant releasedAt,Float score,Float totalScore,
                                String feedback,List<StudentItem> questions,
                                List<PublishedRevision> history,Boolean invalidated,Instant invalidatedAt,String invalidationReason) {
        public StudentResult(UUID sessionId,UUID examId,String title,int attemptNumber,String status,Long resultRevision,Instant submittedAt,Instant gradedAt,Instant releasedAt,Float score,Float totalScore,String feedback,List<StudentItem> questions,List<PublishedRevision> history){
            this(sessionId,examId,title,attemptNumber,status,resultRevision,submittedAt,gradedAt,releasedAt,score,totalScore,feedback,questions,history,null,null,null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StudentItem(UUID questionId,long questionVersion,int position,String questionContent,String questionType,
                              Float pointValue,String answer,String answerDisplay,Float awardedPoints,String feedback,
                              List<String> correctAnswers,String explanation) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublishedRevision(long resultRevision,Instant releasedAt,Float score,Float totalScore,String publicReason) {}
}
