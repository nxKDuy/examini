package com.datn.questionbank.controller;
import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.OrganizationBankService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequiredArgsConstructor
public class OrganizationBankController {
 private final OrganizationBankService service;
 private QuestionActor actor(HttpServletRequest r){return (QuestionActor)r.getAttribute(QuestionActor.class.getName());}
 @PostMapping("/questions/{id}/organization-contributions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public OrganizationContributionResponse submit(@PathVariable UUID id,@Valid @RequestBody OrganizationContributionRequest body,HttpServletRequest r){return service.submit(id,body,actor(r));}
 @GetMapping("/organization-contributions")
 public Page<OrganizationContributionResponse> contributions(@RequestParam UUID bankId,@RequestParam(required=false) OrganizationContribution.ContributionStatus status,
  @PageableDefault(size=20,sort="submittedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){return service.contributions(bankId,status,p,actor(r));}
 @GetMapping("/organization-contributions/{id}")
 public OrganizationContributionResponse contribution(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean includeAnswers,HttpServletRequest r){return service.contribution(id,includeAnswers,actor(r));}
 @PostMapping("/organization-contributions/{id}/review")
 public OrganizationContributionResponse review(@PathVariable UUID id,@Valid @RequestBody PublicReview body,HttpServletRequest r){return service.review(id,body,actor(r));}
 @GetMapping("/organization-questions")
 public Page<OrganizationResponse> publications(@RequestParam UUID bankId,@RequestParam(required=false) Question.QuestionType questionType,@RequestParam(required=false) Question.DifficultyLevel difficulty,
  @PageableDefault(size=20,sort="approvedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){return service.publications(bankId,questionType,difficulty,p,actor(r));}
 @GetMapping("/organization-questions/{id}") public OrganizationResponse publication(@PathVariable UUID id,HttpServletRequest r){return service.publication(id,actor(r));}
 @PostMapping("/organization-questions/{id}/fork") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public BankQuestion fork(@PathVariable UUID id,@Valid @RequestBody ForkRequest body,HttpServletRequest r){return service.fork(id,body,actor(r));}
}
