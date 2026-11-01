package com.datn.grading.controller;
import com.datn.grading.service.GradingService;
import com.datn.grading.dto.SubmissionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import java.util.UUID;
@RestController @RequiredArgsConstructor
public class GradingController {
    private final GradingService service;
    private final com.datn.grading.service.StudentProgressService progress;
    private final com.datn.grading.service.AnswerTutor tutor;
    /** "Why was I wrong?" on one question of the student's own released result (only when the key is visible). */
    /** A fresh practice question on the same skill as a missed question (AI, never stored). */
    @PostMapping("/grades/session/{id}/questions/{questionId}/practice")
    public com.datn.grading.service.AnswerTutor.Practice practice(@PathVariable UUID id,@PathVariable UUID questionId,@RequestParam(defaultValue="vi") String lang,@RequestAttribute UUID actor){
        return tutor.practice(id,questionId,actor,lang);
    }
    @PostMapping("/grades/session/{id}/questions/{questionId}/explain")
    public com.datn.grading.service.AnswerTutor.Explanation explain(@PathVariable UUID id,@PathVariable UUID questionId,@RequestParam(defaultValue="vi") String lang,@RequestAttribute UUID actor){
        return tutor.explain(id,questionId,actor,lang);
    }
    /** The signed-in student's own results across exams (policy-filtered) and study tips. */
    @GetMapping("/grades/my") public com.datn.grading.dto.ProgressDto.Progress myProgress(@RequestAttribute UUID actor){return progress.progress(actor);}
    @GetMapping("/grades/my/insights") public com.datn.grading.dto.ProgressDto.Insights myInsights(@RequestAttribute UUID actor,@RequestParam(defaultValue="false") boolean ai,@RequestParam(defaultValue="vi") String lang){return progress.insights(actor,ai,lang);}
    @GetMapping("/submissions") public Page<SubmissionDto.SummaryResponse> list(@RequestParam UUID examId,@RequestParam(required=false)String status,@RequestParam(required=false)Boolean released,Pageable page,@RequestAttribute UUID actor){return service.getSubmissions(examId,status,released,page,actor);}
    @GetMapping("/submissions/overview") public SubmissionDto.Overview overview(@RequestParam UUID examId,@RequestAttribute UUID actor){return service.overview(examId,actor);}
    @PostMapping("/submissions/{id}/items/{questionId}/ai-suggest") public com.datn.grading.service.GradingService.AiSuggestion aiSuggest(@PathVariable UUID id,@PathVariable UUID questionId,@RequestAttribute UUID actor){return service.suggestEssay(id,questionId,actor);}
    @GetMapping("/submissions/{id}") public SubmissionDto.DetailResponse detail(@PathVariable UUID id,@RequestAttribute UUID actor){return service.detail(id,actor);}
    @PutMapping("/submissions/{id}/draft") public SubmissionDto.DetailResponse draft(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.SaveDraftRequest req){return service.saveDraft(id,req,actor);}
    @PostMapping("/submissions/{id}/finalize") public SubmissionDto.DetailResponse finalizeResult(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.RevisionRequest req){return service.finalizeResult(id,req.expectedRevision(),actor);}
    @PostMapping("/submissions/{id}/release") public SubmissionDto.DetailResponse release(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.RevisionRequest req){return service.release(id,req.expectedRevision(),actor);}
    @PostMapping("/submissions/{id}/regrade") public SubmissionDto.DetailResponse regrade(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.RegradeRequest req){return service.beginRegrade(id,req,actor);}
    @GetMapping("/grades/session/{id}") public SubmissionDto.StudentResult result(@PathVariable UUID id,@RequestAttribute UUID actor){return service.student(id,actor);}
}
