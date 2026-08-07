package com.datn.grading.controller;

import com.datn.grading.dto.SubmissionDto;
import com.datn.grading.entity.ExamResult;
import com.datn.grading.repository.ExamResultRepository;
import com.datn.grading.service.GradingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Tag(name = "Grading & Submissions", description = "Quản lý điểm số và bài nộp")
public class GradingController {

    private final ExamResultRepository resultRepo;
    private final GradingService gradingService;

    @GetMapping("/submissions")
    @Operation(summary = "Lấy danh sách bài nộp có phân trang và lọc")
    public Page<SubmissionDto.SummaryResponse> getSubmissions(
            @RequestParam(required = false) UUID examId,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "gradedAt") Pageable pageable) {
        return gradingService.getSubmissions(examId, status, pageable);
    }

    @GetMapping("/submissions/{id}")
    @Operation(summary = "Lấy chi tiết bài nộp theo ID")
    public SubmissionDto.DetailResponse getSubmissionDetail(@PathVariable UUID id) {
        return gradingService.getSubmissionDetail(id);
    }

    @PutMapping("/submissions/{id}/grade")
    @Operation(summary = "Chấm điểm / nhận xét thủ công cho bài nộp")
    public SubmissionDto.SummaryResponse gradeSubmission(
            @PathVariable UUID id,
            @RequestBody SubmissionDto.ManualGradeRequest req) {
        return gradingService.gradeSubmission(id, req);
    }

    @GetMapping("/grades/session/{sessionId}")
    @Operation(summary = "Lấy điểm của 1 phiên thi")
    public ExamResult getResultBySession(@PathVariable UUID sessionId) {
        return resultRepo.findBySessionId(sessionId).orElse(null);
    }

    @GetMapping("/grades/exam/{examId}/stats")
    @Operation(summary = "Thống kê điểm số của 1 đề thi (cho analytics)")
    public ExamStats getStatsByExam(@PathVariable UUID examId) {
        List<ExamResult> results = resultRepo.findAll().stream()
                .filter(r -> r.getExamId().equals(examId))
                .toList();

        if (results.isEmpty()) return new ExamStats(0, 0, 0, 0);

        DoubleSummaryStatistics stats = results.stream()
                .filter(r -> r.getTotalScore() != null)
                .collect(Collectors.summarizingDouble(ExamResult::getTotalScore));

        return new ExamStats(
                stats.getCount(),
                (float) stats.getAverage(),
                (float) stats.getMax(),
                (float) stats.getMin()
        );
    }

    public record ExamStats(long totalParticipants, float averageScore, float maxScore, float minScore) {}
}
