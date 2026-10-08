package com.datn.questionbank.dto;
import com.datn.questionbank.entity.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
public class BankDto {
    public record CreateBank(@NotNull QuestionBank.BankType type,@NotBlank @Size(max=200) String name,UUID organizationId) {}
    public record BankResponse(UUID id,QuestionBank.BankType type,UUID ownerUserId,UUID organizationId,String name,QuestionBank.BankStatus status,boolean defaultBank,LocalDateTime createdAt) {}
    public record BankQuestion(UUID bankId,QuestionDto.QuestionProjection question) {}
    public record ForkRequest(@Positive long sourceQuestionVersion,UUID targetBankId) {}
    public record ContributionRequest(@Positive long sourceQuestionVersion) {}
    public record PublicReview(@NotNull QuestionReview.Decision decision,@Size(max=5000) String reviewNote) {}
    public enum LineageRelation { SOURCE, PUBLICATION, COPY }
    /** name is the Question Bank display name, never an identity/email or question content. */
    public record LineageNode(LineageRelation relation,UUID id,long version,UUID bankId,QuestionBank.BankType bankType,
        String status,String name,boolean available,boolean frozen) {}
    public record LineageResponse(UUID questionId,UUID sourceQuestionId,long sourceQuestionVersion,UUID sourceBankId,
        QuestionBank.BankType sourceBankType,UUID sourcePublicQuestionId,UUID sourceOrganizationQuestionId,
        LocalDateTime copiedAt,UUID copiedBy,java.util.List<LineageNode> nodes) {}
    public record ContributionResponse(UUID id,UUID sourceQuestionId,long sourceQuestionVersion,UUID sourceBankId,UUID sourceOwnerUserId,UUID submittedBy,LocalDateTime submittedAt,PublicContribution.VerificationStatus status,UUID reviewerId,String reviewNote,LocalDateTime reviewedAt,QuestionDto.QuestionProjection question) {}
    public record PublicResponse(UUID id,UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion,UUID sourceBankId,UUID sourceOwnerUserId,
        String sourceOwnerDisplayName,PublicQuestion.PublicationStatus publicationStatus,UUID publishedBy,LocalDateTime publishedAt,
        UUID archivedBy,LocalDateTime archivedAt,QuestionDto.SafeQuestionResponse question,PublicQuestion.PostType postType,String title,
        String description,long postVersion,int itemCount,List<PublicPostItemResponse> items,List<PublicSubject> subjects,
        List<PublicCategory> categories,long copies24h,long copies30d,long copies365d) {}
    public record PublicPostItemResponse(UUID id,int position,UUID sourceQuestionId,long sourceQuestionVersion,
        QuestionDto.SafeQuestionResponse question) {}
    public record PublicSubject(UUID id,String name) {}
    public record PublicCategory(UUID id,String name,UUID subjectId,String subjectName,UUID parentCategoryId) {}
    public record SetItemRequest(@NotNull UUID questionId,@NotNull @Positive Long questionVersion) {}
    public record QuestionSetWrite(@NotBlank @Size(max=200) String title,@Size(max=2000) String description,
        @NotNull @Size(min=1,max=100) List<@NotNull @Valid SetItemRequest> items,@Positive Long expectedVersion) {}
    public record PublishSetRequest(@NotNull @Positive Long version) {}
    public record QuestionSetResponse(UUID id,UUID bankId,UUID ownerUserId,String title,String description,long version,
        QuestionSet.SetStatus status,LocalDateTime updatedAt,List<PublicPostItemResponse> items) {}
    public record QuestionSetForkRequest(UUID targetBankId) {}
    public record OrganizationContributionRequest(@NotNull UUID bankId,@Positive long sourceQuestionVersion,
        @Size(max=5000) String submissionNote) {}
    public record OrganizationContributionResponse(UUID id,UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion,
        UUID sourceOwnerUserId,String submissionNote,OrganizationContribution.ContributionStatus status,UUID reviewerId,String reviewNote,
        LocalDateTime submittedAt,LocalDateTime reviewedAt,QuestionDto.QuestionProjection question) {}
    public record OrganizationResponse(UUID id,UUID bankId,UUID sourceQuestionId,long sourceQuestionVersion,
        UUID sourceOwnerUserId,String publicationStatus,UUID approvedBy,LocalDateTime approvedAt,QuestionDto.SafeQuestionResponse question) {}
}
