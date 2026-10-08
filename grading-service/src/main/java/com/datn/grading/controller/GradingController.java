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
    @GetMapping("/submissions") public Page<SubmissionDto.SummaryResponse> list(@RequestParam UUID examId,@RequestParam(required=false)String status,@RequestParam(required=false)Boolean released,Pageable page,@RequestAttribute UUID actor){return service.getSubmissions(examId,status,released,page,actor);}
    @GetMapping("/submissions/overview") public SubmissionDto.Overview overview(@RequestParam UUID examId,@RequestAttribute UUID actor){return service.overview(examId,actor);}
    @GetMapping("/submissions/{id}") public SubmissionDto.DetailResponse detail(@PathVariable UUID id,@RequestAttribute UUID actor){return service.detail(id,actor);}
    @PutMapping("/submissions/{id}/draft") public SubmissionDto.DetailResponse draft(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.SaveDraftRequest req){return service.saveDraft(id,req,actor);}
    @PostMapping("/submissions/{id}/finalize") public SubmissionDto.DetailResponse finalizeResult(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.RevisionRequest req){return service.finalizeResult(id,req.expectedRevision(),actor);}
    @PostMapping("/submissions/{id}/release") public SubmissionDto.DetailResponse release(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.RevisionRequest req){return service.release(id,req.expectedRevision(),actor);}
    @PostMapping("/submissions/{id}/regrade") public SubmissionDto.DetailResponse regrade(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.RegradeRequest req){return service.beginRegrade(id,req,actor);}
    @GetMapping("/grades/session/{id}") public SubmissionDto.StudentResult result(@PathVariable UUID id,@RequestAttribute UUID actor){return service.student(id,actor);}
}
