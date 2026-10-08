package com.datn.questionbank.dto;
import com.datn.questionbank.entity.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.UUID;
public class BankDto {
    public record CreateBank(@NotNull QuestionBank.BankType type,@NotBlank @Size(max=200) String name,UUID organizationId) {}
    public record BankResponse(UUID id,QuestionBank.BankType type,UUID ownerUserId,UUID organizationId,String name,QuestionBank.BankStatus status,boolean defaultBank,LocalDateTime createdAt) {}
    public record BankQuestion(UUID bankId,QuestionDto.QuestionProjection question) {}
    public record ForkRequest(@Positive long sourceQuestionVersion,UUID targetBankId) {}
    public record ContributionRequest(@Positive long sourceQuestionVersion) {}
    public record PublicReview(@NotNull QuestionReview.Decision decision,@Size(max=5000) String reviewNote) {}
    public record LineageResponse(UUID questionId,UUID sourceQuestionId,long sourceQuestionVersion,UUID sourceBankId,QuestionBank.BankType sourceBankType,UUID sourcePublicQuestionId,UUID sourceOrganizationQuestionId,LocalDateTime copiedAt,UUID copiedBy) {}
    public record ContributionResponse(UUID id,UUID sourceQuestionId,long sourceQuestionVersion,UUID sourceBankId,UUID sourceOwnerUserId,UUID submittedBy,LocalDateTime submittedAt,PublicContribution.VerificationStatus status,UUID reviewerId,String reviewNote,LocalDateTime reviewedAt,QuestionDto.QuestionProjection question) {}
    public record PublicResponse(UUID id,UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion,UUID sourceBankId,UUID sourceOwnerUserId,String publicationStatus,UUID publishedBy,LocalDateTime publishedAt,QuestionDto.SafeQuestionResponse question) {}
    public record OrganizationContributionRequest(@NotNull UUID bankId,@Positive long sourceQuestionVersion) {}
    public record OrganizationContributionResponse(UUID id,UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion,
        UUID sourceOwnerUserId,OrganizationContribution.ContributionStatus status,UUID reviewerId,String reviewNote,
        LocalDateTime submittedAt,LocalDateTime reviewedAt,QuestionDto.QuestionProjection question) {}
    public record OrganizationResponse(UUID id,UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion,
        UUID sourceOwnerUserId,String publicationStatus,UUID approvedBy,LocalDateTime approvedAt,QuestionDto.SafeQuestionResponse question) {}
}
