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
    @GetMapping("/submissions") public Page<SubmissionDto.SummaryResponse> list(@RequestParam UUID examId,@RequestParam(required=false)String status,Pageable page,@RequestAttribute UUID actor){return service.getSubmissions(examId,status,page,actor);}
    @GetMapping("/submissions/{id}") public SubmissionDto.DetailResponse detail(@PathVariable UUID id,@RequestAttribute UUID actor){return service.detail(id,actor);}
    @PutMapping("/submissions/{id}/grade") public SubmissionDto.SummaryResponse grade(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody SubmissionDto.ManualGradeRequest req){return service.grade(id,req,actor);}
    @GetMapping("/grades/session/{id}") public GradingService.StudentResult result(@PathVariable UUID id,@RequestAttribute UUID actor){return service.student(id,actor);}
}
