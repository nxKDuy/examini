package com.datn.questionbank.controller;
import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.dto.QuestionDto.CreateRequest;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.MultiBankService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
@RestController @RequiredArgsConstructor
public class MultiBankController {
    private final MultiBankService service;
    private QuestionActor actor(HttpServletRequest r) {
        if(r.getAttribute(QuestionActor.class.getName()) instanceof QuestionActor a)return a;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Yêu cầu xác thực");
    }
    @GetMapping("/question-banks")
    public Page<BankResponse> banks(@RequestParam(required=false) QuestionBank.BankType type,@RequestParam(required=false) UUID ownerUserId,
            @RequestParam(required=false) UUID organizationId,@PageableDefault(size=20,sort="createdAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r) {
        return service.listBanks(type,ownerUserId,organizationId,p,actor(r));
    }
    @PostMapping("/question-banks") @ResponseStatus(HttpStatus.CREATED)
    public BankResponse createBank(@Valid @RequestBody CreateBank b,HttpServletRequest r){return service.createBank(b,actor(r));}
    @GetMapping("/question-banks/{id}") public BankResponse bank(@PathVariable UUID id,HttpServletRequest r){return service.bank(id,actor(r));}
    @GetMapping("/question-banks/{id}/questions")
    public Page<BankQuestion> questions(@PathVariable UUID id,@RequestParam(required=false) Question.QuestionType questionType,
            @RequestParam(required=false) Question.DifficultyLevel difficulty,@RequestParam(required=false) Question.QuestionStatus status,
            @PageableDefault(size=20,sort="createdAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r) {
        return service.listQuestions(id,questionType,difficulty,status,p,actor(r));
    }
    @PostMapping("/question-banks/{id}/questions") @ResponseStatus(HttpStatus.CREATED)
    public BankQuestion createQuestion(@PathVariable UUID id,@Valid @RequestBody CreateRequest b,HttpServletRequest r){return service.createQuestion(id,b,actor(r));}
    @GetMapping("/questions/{id}/lineage") public LineageResponse lineage(@PathVariable UUID id,HttpServletRequest r){return service.lineage(id,actor(r));}
    @PostMapping("/questions/{id}/fork") @ResponseStatus(HttpStatus.CREATED)
    public BankQuestion fork(@PathVariable UUID id,@Valid @RequestBody ForkRequest b,HttpServletRequest r){return service.forkQuestion(id,b,actor(r));}
    @PostMapping("/questions/{id}/contributions") @ResponseStatus(HttpStatus.CREATED)
    public ContributionResponse contribute(@PathVariable UUID id,@Valid @RequestBody ContributionRequest b,HttpServletRequest r){return service.contribute(id,b,actor(r));}
    @GetMapping("/public-contributions") public Page<ContributionResponse> contributions(@RequestParam(required=false) PublicContribution.VerificationStatus status,
            @PageableDefault(size=20,sort="submittedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){return service.listContributions(status,p,actor(r));}
    @GetMapping("/public-contributions/{id}") public ContributionResponse contribution(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean includeAnswers,HttpServletRequest r){return service.contribution(id,includeAnswers,actor(r));}
    @PostMapping("/public-contributions/{id}/review") public ContributionResponse review(@PathVariable UUID id,@Valid @RequestBody PublicReview b,HttpServletRequest r){return service.reviewContribution(id,b,actor(r));}
    @GetMapping("/public-questions") public Page<PublicResponse> publications(@RequestParam(required=false) UUID bankId,@RequestParam(required=false) UUID sourceOwnerUserId,
            @RequestParam(required=false) Question.QuestionType questionType,@RequestParam(required=false) Question.DifficultyLevel difficulty,
            @PageableDefault(size=20,sort="verifiedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){actor(r);return service.publicQuestions(bankId,sourceOwnerUserId,questionType,difficulty,p);}
    @GetMapping("/public-questions/{id}") public PublicResponse publication(@PathVariable UUID id,HttpServletRequest r){actor(r);return service.publicQuestion(id);}
    @PostMapping("/public-questions/{id}/fork") @ResponseStatus(HttpStatus.CREATED)
    public BankQuestion forkPublic(@PathVariable UUID id,@Valid @RequestBody ForkRequest b,HttpServletRequest r){return service.forkPublic(id,b,actor(r));}
}
