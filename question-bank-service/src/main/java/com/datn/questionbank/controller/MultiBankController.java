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
    private final com.datn.questionbank.service.QuestionSetService sets;
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
    @GetMapping("/question-banks/{id}/question-sets")
    public Page<QuestionSetResponse> questionSets(@PathVariable UUID id,@PageableDefault(size=20,sort="updatedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){return sets.list(id,p,actor(r));}
    @PostMapping("/question-banks/{id}/question-sets") @ResponseStatus(HttpStatus.CREATED)
    public QuestionSetResponse createSet(@PathVariable UUID id,@Valid @RequestBody QuestionSetWrite b,HttpServletRequest r){return sets.create(id,b,actor(r));}
    @GetMapping("/question-sets/{id}") public QuestionSetResponse questionSet(@PathVariable UUID id,HttpServletRequest r){return sets.get(id,actor(r));}
    @GetMapping("/question-sets/{id}/versions/{version}") public QuestionSetResponse questionSetVersion(@PathVariable UUID id,@PathVariable long version,HttpServletRequest r){return sets.version(id,version,actor(r));}
    @PutMapping("/question-sets/{id}") public QuestionSetResponse updateSet(@PathVariable UUID id,@Valid @RequestBody QuestionSetWrite b,HttpServletRequest r){return sets.update(id,b,actor(r));}
    @DeleteMapping("/question-sets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archiveSet(@PathVariable UUID id,HttpServletRequest r){sets.archive(id,actor(r));}
    @PostMapping("/question-sets/{id}/publish-public") @ResponseStatus(HttpStatus.CREATED)
    public PublicResponse publishSet(@PathVariable UUID id,@Valid @RequestBody PublishSetRequest b,HttpServletRequest r){return sets.publish(id,b,actor(r));}
    @GetMapping("/questions/{id}/lineage") public LineageResponse lineage(@PathVariable UUID id,HttpServletRequest r){return service.lineage(id,actor(r));}
    @PostMapping("/questions/{id}/fork") @ResponseStatus(HttpStatus.CREATED)
    public BankQuestion fork(@PathVariable UUID id,@Valid @RequestBody ForkRequest b,HttpServletRequest r){return service.forkQuestion(id,b,actor(r));}
    @PostMapping("/questions/{id}/publish-public") @ResponseStatus(HttpStatus.CREATED)
    public PublicResponse publish(@PathVariable UUID id,@Valid @RequestBody ContributionRequest b,HttpServletRequest r){return service.publish(id,b,actor(r));}
    @PostMapping("/questions/{id}/contributions") @ResponseStatus(HttpStatus.CREATED)
    public ContributionResponse contribute(@PathVariable UUID id,@Valid @RequestBody ContributionRequest b,HttpServletRequest r){return service.contribute(id,b,actor(r));}
    @GetMapping("/public-contributions") public Page<ContributionResponse> contributions(@RequestParam(required=false) PublicContribution.VerificationStatus status,
            @PageableDefault(size=20,sort="submittedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){return service.listContributions(status,p,actor(r));}
    @GetMapping("/public-contributions/{id}") public ContributionResponse contribution(@PathVariable UUID id,@RequestParam(defaultValue="false") boolean includeAnswers,HttpServletRequest r){return service.contribution(id,includeAnswers,actor(r));}
    @PostMapping("/public-contributions/{id}/review") public ContributionResponse review(@PathVariable UUID id,@Valid @RequestBody PublicReview b,HttpServletRequest r){return service.reviewContribution(id,b,actor(r));}
    @GetMapping("/public-questions") public Page<PublicResponse> publications(@RequestParam(required=false) UUID bankId,@RequestParam(required=false) UUID sourceOwnerUserId,
            @RequestParam(required=false) Question.QuestionType questionType,@RequestParam(required=false) Question.DifficultyLevel difficulty,
            @RequestParam(required=false) String text,@RequestParam(required=false) PublicQuestion.PublicationStatus publicationStatus,
            @RequestParam(defaultValue="false") boolean mine,@RequestParam(required=false) UUID subjectId,@RequestParam(required=false) UUID categoryId,
            @RequestParam(required=false) PublicQuestion.PostType postType,@RequestParam(defaultValue="NEWEST") String ranking,
            @PageableDefault(size=20,sort="verifiedAt",direction=Sort.Direction.DESC) Pageable p,HttpServletRequest r){var actor=actor(r);return service.publicQuestions(bankId,sourceOwnerUserId,questionType,difficulty,text,publicationStatus,mine,subjectId,categoryId,postType,ranking,p,actor);}
    @GetMapping("/public-questions/{id}") public PublicResponse publication(@PathVariable UUID id,HttpServletRequest r){return service.publicQuestion(id,actor(r));}
    @PutMapping("/public-questions/{id}/rating") public PublicRatingResponse ratePublic(@PathVariable UUID id,@Valid @RequestBody PublicRatingRequest b,HttpServletRequest r){return service.ratePublic(id,b,actor(r));}
    @PostMapping("/public-questions/{id}/view") public PublicViewResponse recordPublicView(@PathVariable UUID id,HttpServletRequest r){return service.recordPublicView(id,actor(r));}
    @DeleteMapping("/public-questions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archivePublication(@PathVariable UUID id,HttpServletRequest r){service.archivePublic(id,actor(r));}
    @PostMapping("/public-questions/{id}/fork") @ResponseStatus(HttpStatus.CREATED)
    public BankQuestion forkPublic(@PathVariable UUID id,@Valid @RequestBody ForkRequest b,HttpServletRequest r){return service.forkPublic(id,b,actor(r));}
    @PostMapping("/public-questions/{id}/fork-items/{itemId}") @ResponseStatus(HttpStatus.CREATED)
    public BankQuestion forkPublicItem(@PathVariable UUID id,@PathVariable UUID itemId,@Valid @RequestBody ForkRequest b,HttpServletRequest r){return service.forkPublicItem(id,itemId,b,actor(r));}
    @PostMapping("/public-questions/{id}/fork-set") @ResponseStatus(HttpStatus.CREATED)
    public QuestionSetResponse forkPublicSet(@PathVariable UUID id,@Valid @RequestBody QuestionSetForkRequest b,HttpServletRequest r){return sets.fork(id,b,actor(r));}
}
