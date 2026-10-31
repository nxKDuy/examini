package com.datn.questionbank.controller;

import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.Question.*;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.ExamEligibilityService;
import com.datn.questionbank.service.QuestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionService service;
    private final ExamEligibilityService eligibility;
    private final com.datn.questionbank.service.ai.AiQuestionReview review;
    private final com.datn.questionbank.service.ai.AiMatrixPlanner planner;

    private QuestionActor actor(HttpServletRequest request) {
        Object actor = request.getAttribute(QuestionActor.class.getName());
        if (!(actor instanceof QuestionActor)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Yêu cầu xác thực");
        return (QuestionActor) actor;
    }

    @GetMapping
    public Page<QuestionProjection> findAll(@RequestParam(required=false) UUID categoryId,
            @RequestParam(required=false) DifficultyLevel difficulty, @RequestParam(required=false) QuestionType questionType,
            @RequestParam(required=false) QuestionStatus status, @RequestParam(required=false) CreationMethod creationMethod,
            @RequestParam(required=false) SourceType sourceType, @RequestParam(required=false) String text,
            @RequestParam(required=false) QuestionScope scope,
            @PageableDefault(size=20, sort="createdAt", direction=Sort.Direction.DESC) Pageable pageable,
            HttpServletRequest request) {
        if (pageable.getPageSize() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size tối đa 100");
        return service.findAll(categoryId, difficulty, questionType, status, creationMethod, sourceType, text, scope, pageable, actor(request));
    }
    @GetMapping("/eligible")
    public Page<QuestionProjection> eligible(@RequestParam(required=false) UUID bankId, @RequestParam(required=false) UUID categoryId,
            @RequestParam(required=false) QuestionType questionType, @RequestParam(required=false) DifficultyLevel difficulty,
            @RequestParam(required=false) String text, @PageableDefault(size=20) Pageable pageable, HttpServletRequest req) {
        return eligibility.page(new EligibleFilter(bankId, categoryId, questionType, difficulty, text), pageable, actor(req));
    }
    @PostMapping("/eligible/ids")
    public EligibleIdsResponse eligibleIds(@Valid @RequestBody EligibleFilter body, HttpServletRequest req) {
        return eligibility.ids(body, actor(req));
    }
    /** Reading/listening passages the author already used, to put another question under the same passage. */
    @GetMapping("/passages")
    public java.util.List<com.datn.questionbank.dto.Presentation.Passage> passages(HttpServletRequest req) {
        return service.passages(actor(req));
    }
    @PostMapping("/eligible/matrix-counts")
    public List<MatrixCount> matrixCounts(@Valid @RequestBody MatrixCountsRequest body, HttpServletRequest req) {
        return eligibility.matrixCounts(body, actor(req));
    }
    @PostMapping("/eligible/random")
    public EligibleRandomResponse eligibleRandom(@Valid @RequestBody EligibleRandomRequest body, HttpServletRequest req) {
        return eligibility.pick(body, actor(req));
    }
    /** Exam matrix from a sentence (AI fills the counts over the given topics; the teacher reviews it). */
    @PostMapping("/ai/matrix-plan")
    public com.datn.questionbank.service.ai.AiMatrixPlanner.Plan matrixPlan(@RequestBody com.datn.questionbank.service.ai.AiMatrixPlanner.Request body, HttpServletRequest req) { return planner.plan(body, actor(req)); }
    /** AI review of one question (rule checks + independent solve + short critique); changes nothing. */
    @PostMapping("/{id}/ai-review")
    public com.datn.questionbank.service.ai.AiQuestionReview.Review aiReview(@PathVariable UUID id, HttpServletRequest req) { return review.review(id, actor(req)); }
    @GetMapping("/{id}")
    public QuestionProjection findById(@PathVariable UUID id, @RequestParam(defaultValue="false") boolean includeAnswers, HttpServletRequest req) { return service.findById(id, includeAnswers, actor(req)); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionResponse create(@Valid @RequestBody CreateRequest body, HttpServletRequest req) { return service.create(body, actor(req)); }
    @PutMapping("/{id}")
    public QuestionResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest body, HttpServletRequest req) {
        return service.update(id, body, actor(req));
    }
    @PostMapping("/{id}/activate")
    public QuestionResponse activate(@PathVariable UUID id, @Valid @RequestBody RevisionRequest body, HttpServletRequest req) {
        return service.activate(id, body.questionVersion(), actor(req));
    }
    @PostMapping("/{id}/submit-review")
    public QuestionResponse submit(@PathVariable UUID id, @Valid @RequestBody RevisionRequest body, HttpServletRequest req) {
        return service.submit(id, body.questionVersion(), actor(req));
    }
    @PostMapping("/{id}/review")
    public QuestionResponse review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest body, HttpServletRequest req) {
        return service.review(id, body, actor(req));
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id, @RequestParam long questionVersion, HttpServletRequest req) {
        service.archive(id, questionVersion, actor(req));
    }
    @GetMapping("/{id}/versions")
    public List<VersionResponse> history(@PathVariable UUID id, @RequestParam(defaultValue="false") boolean includeAnswers, HttpServletRequest req) { return service.history(id, includeAnswers, actor(req)); }
    @GetMapping("/{id}/versions/{version}")
    public VersionResponse version(@PathVariable UUID id, @PathVariable long version, @RequestParam(defaultValue="false") boolean includeAnswers, HttpServletRequest req) {
        return service.version(id, version, includeAnswers, actor(req));
    }
    @GetMapping("/{id}/reviews")
    public List<ReviewResponse> reviews(@PathVariable UUID id, HttpServletRequest req) { return service.reviewHistory(id, actor(req)); }
    @GetMapping("/random")
    public List<UUID> random(@RequestParam UUID categoryId, @RequestParam DifficultyLevel difficulty,
            @RequestParam(defaultValue="10") int limit, @RequestParam(required=false) QuestionType questionType,
            @RequestParam(required=false) QuestionStatus status, @RequestParam(required=false) UUID bankId, HttpServletRequest req) {
        if (status != null && status != QuestionStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exam selection dùng bank-aware eligibility; status chỉ hỗ trợ ACTIVE");
        return service.getRandomQuestionIds(categoryId, difficulty, limit, questionType, bankId, actor(req));
    }
    @PostMapping("/batch")
    public List<QuestionProjection> batch(@RequestBody List<UUID> ids, @RequestParam(defaultValue="true") boolean eligible,
            @RequestParam(defaultValue="false") boolean includeAnswers, HttpServletRequest req) {
        return service.getByIds(ids, eligible, includeAnswers, actor(req));
    }
    @PostMapping(value="/import/csv", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importCsv(@RequestParam MultipartFile file, @RequestParam UUID categoryId, HttpServletRequest req) {
        return service.importCsv(file, categoryId, actor(req));
    }
}
