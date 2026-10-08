package com.datn.questionbank.service;
import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.dto.QuestionDto.QuestionResponse;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class OrganizationBankService {
 private final BankRegistry banks;
 private final QuestionBankRepository bankRepo;
 private final OrganizationContributionRepository contributions;
 private final OrganizationQuestionRepository publications;
 private final QuestionRepository questions;
 private final QuestionVersionRepository versions;
 private final QuestionLineageRepository lineage;
 private final QuestionPolicy policy;
 private final QuestionService core;
 private final ObjectMapper json;
 @Transactional public OrganizationContributionResponse submit(UUID questionId,OrganizationContributionRequest request,QuestionActor actor) {
  var bank=banks.get(request.bankId());if(bank.getType()!=QuestionBank.BankType.ORGANIZATION) throw error(HttpStatus.BAD_REQUEST,"Target bank phải ORGANIZATION");
  banks.requireSubmit(bank.getId(),actor);
  var q=questions.findForUpdate(questionId).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Không tìm thấy question"));
  policy.requireOwner(q,actor);
  if(q.getScope()!=Question.QuestionScope.PERSONAL||q.getStatus()!=Question.QuestionStatus.ACTIVE||q.getQuestionVersion()!=request.sourceQuestionVersion())
   throw error(HttpStatus.CONFLICT,"Contribution yêu cầu current PERSONAL ACTIVE source version");
  if(contributions.existsByBankIdAndSourceQuestionIdAndSourceQuestionVersion(bank.getId(),questionId,q.getQuestionVersion())) throw error(HttpStatus.CONFLICT,"Version đã được gửi tới bank");
  var frozen=read(versions.findByQuestionIdAndVersionNumber(questionId,q.getQuestionVersion()).orElseThrow().getSnapshot()).withContext(Question.QuestionStatus.ACTIVE,q.getUpdatedAt());
  var c=contributions.save(OrganizationContribution.builder().bankId(bank.getId()).sourceQuestionId(questionId).sourceQuestionVersion(q.getQuestionVersion())
   .sourceBankId(q.getBankId()).sourceOwnerUserId(q.getCreatedBy()).snapshot(write(frozen))
   .status(OrganizationContribution.ContributionStatus.PENDING_REVIEW).submittedBy(actor.userId()).submittedAt(LocalDateTime.now()).build());
  return response(c,false);
 }
 public OrganizationContributionResponse contribution(UUID id,boolean answers,QuestionActor actor) {
  var c=contributions.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Không tìm thấy contribution"));
  if(!c.getSubmittedBy().equals(actor.userId())) banks.requireReview(c.getBankId(),actor);
  if(answers) banks.requireReview(c.getBankId(),actor);
  return response(c,answers);
 }
 public Page<OrganizationContributionResponse> contributions(UUID bankId,OrganizationContribution.ContributionStatus status,Pageable page,QuestionActor actor) {
  bounds(page);banks.requireRead(banks.get(bankId),actor);
  Specification<OrganizationContribution> spec=(r,q,b)->b.equal(r.get("bankId"),bankId);
  banks.requireReview(bankId,actor);
  if(status!=null) spec=spec.and((r,q,b)->b.equal(r.get("status"),status));
  return contributions.findAll(spec,page).map(c->response(c,false));
 }
 @Transactional public OrganizationContributionResponse review(UUID id,PublicReview request,QuestionActor actor) {
  var c=contributions.findForUpdate(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Không tìm thấy contribution"));
  banks.requireReview(c.getBankId(),actor);
  if(c.getSubmittedBy().equals(actor.userId())) throw error(HttpStatus.FORBIDDEN,"Không được self-review contribution");
  if(c.getStatus()!=OrganizationContribution.ContributionStatus.PENDING_REVIEW) throw error(HttpStatus.CONFLICT,"Contribution đã được quyết định");
  if(request.decision()==QuestionReview.Decision.REJECTED&&(request.reviewNote()==null||request.reviewNote().isBlank())) throw error(HttpStatus.BAD_REQUEST,"Reject cần ghi chú");
  c.setStatus(request.decision()==QuestionReview.Decision.APPROVED?OrganizationContribution.ContributionStatus.APPROVED:OrganizationContribution.ContributionStatus.REJECTED);
  c.setReviewerId(actor.userId());c.setReviewNote(request.reviewNote());c.setReviewedAt(LocalDateTime.now());
  if(c.getStatus()==OrganizationContribution.ContributionStatus.APPROVED) publications.save(OrganizationQuestion.builder().bankId(c.getBankId()).contributionId(c.getId())
   .sourceQuestionId(c.getSourceQuestionId()).sourceQuestionVersion(c.getSourceQuestionVersion()).sourceBankId(c.getSourceBankId()).sourceOwnerUserId(c.getSourceOwnerUserId())
   .snapshot(c.getSnapshot()).questionType(read(c.getSnapshot()).questionType()).difficultyLevel(read(c.getSnapshot()).difficultyLevel()).approvedBy(actor.userId()).approvedAt(c.getReviewedAt()).build());
  return response(c,false);
 }
 public Page<OrganizationResponse> publications(UUID bankId,Question.QuestionType type,Question.DifficultyLevel difficulty,Pageable page,QuestionActor actor) {
  bounds(page);banks.requireUse(bankId,actor);
  Specification<OrganizationQuestion> spec=(r,q,b)->b.equal(r.get("bankId"),bankId);
  if(type!=null)spec=spec.and((r,q,b)->b.equal(r.get("questionType"),type));
  if(difficulty!=null)spec=spec.and((r,q,b)->b.equal(r.get("difficultyLevel"),difficulty));
  return publications.findAll(spec,page).map(this::response);
 }
 public OrganizationResponse publication(UUID id,QuestionActor actor) {var p=get(id);banks.requireUse(p.getBankId(),actor);return response(p);}
 @Transactional public BankQuestion fork(UUID id,ForkRequest request,QuestionActor actor) {
  policy.requireAuthor(actor);var p=get(id);banks.requireUse(p.getBankId(),actor);
  if(request.sourceQuestionVersion()!=p.getSourceQuestionVersion()) throw error(HttpStatus.CONFLICT,"Source version không khớp");
  UUID target=request.targetBankId()==null?banks.defaultPersonal(actor.userId()):request.targetBankId();
  var copied=core.copySnapshot(read(p.getSnapshot()),target,Question.SourceType.ORGANIZATION_BANK,id.toString(),actor);
  lineage.save(QuestionLineage.builder().questionId(copied.id()).sourceQuestionId(p.getSourceQuestionId()).sourceQuestionVersion(p.getSourceQuestionVersion())
   .sourceBankId(p.getBankId()).sourceBankType(QuestionBank.BankType.ORGANIZATION).sourceOrganizationQuestionId(id).copiedBy(actor.userId()).copiedAt(LocalDateTime.now()).build());
  return new BankQuestion(target,copied);
 }
 private OrganizationQuestion get(UUID id){return publications.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Không tìm thấy publication"));}
 private OrganizationContributionResponse response(OrganizationContribution c,boolean answers){var q=read(c.getSnapshot());return new OrganizationContributionResponse(c.getId(),c.getBankId(),c.getSourceQuestionId(),c.getSourceQuestionVersion(),c.getSourceOwnerUserId(),c.getStatus(),c.getReviewerId(),c.getReviewNote(),c.getSubmittedAt(),c.getReviewedAt(),answers?q:q.withoutAnswers());}
 private OrganizationResponse response(OrganizationQuestion p){return new OrganizationResponse(p.getId(),p.getBankId(),p.getSourceQuestionId(),p.getSourceQuestionVersion(),p.getSourceOwnerUserId(),"APPROVED",p.getApprovedBy(),p.getApprovedAt(),read(p.getSnapshot()).withoutAnswers());}
 private QuestionResponse read(String s){try{return json.readValue(s,QuestionResponse.class);}catch(Exception e){throw new IllegalStateException("Invalid frozen snapshot",e);}}
 private String write(QuestionResponse q){try{return json.writeValueAsString(q);}catch(Exception e){throw new IllegalStateException("Invalid frozen snapshot",e);}}
 private void bounds(Pageable p){if(p.getPageSize()>100)throw error(HttpStatus.BAD_REQUEST,"size tối đa 100");}
 private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
