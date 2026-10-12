package com.datn.analytics.controller;

import com.datn.analytics.query.AnalyticsDto;
import com.datn.analytics.query.ExamAnalyticsQueries;
import com.datn.analytics.security.ExamAnalyticsAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/analytics/exams/{examId}")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Báo cáo thống kê cho giáo viên")
public class AnalyticsController {
    private static final Set<String> STATUSES = Set.of("PENDING", "NEEDS_REVIEW", "GRADED");

    private final ExamAnalyticsAccess access;
    private final ExamAnalyticsQueries queries;

    @GetMapping("/summary")
    @Operation(summary = "Tổng quan điểm của một đề thi")
    public AnalyticsDto.ExamSummary summary(@PathVariable UUID examId, @RequestAttribute UUID actor) {
        access.require(examId, actor);
        return queries.summary(examId);
    }

    @GetMapping("/questions")
    @Operation(summary = "Thống kê theo từng câu hỏi (theo phiên bản snapshot)")
    public List<AnalyticsDto.QuestionStats> questions(@PathVariable UUID examId, @RequestAttribute UUID actor) {
        access.require(examId, actor);
        return queries.questions(examId);
    }

    @GetMapping("/results")
    @Operation(summary = "Kết quả theo từng lượt làm bài")
    public AnalyticsDto.PageResponse<AnalyticsDto.CandidateResult> results(
            @PathVariable UUID examId, @RequestAttribute UUID actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID candidateId,
            @RequestParam(required = false) Boolean invalidated,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (status != null && !STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown grading status");
        validatePage(page, size);
        access.require(examId, actor);
        return queries.results(examId, status, candidateId, invalidated, page, size);
    }

    @GetMapping("/students")
    @Operation(summary = "Tóm tắt các lượt nộp hợp lệ theo Student")
    public AnalyticsDto.PageResponse<AnalyticsDto.StudentSummary> students(
            @PathVariable UUID examId, @RequestAttribute UUID actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        access.require(examId, actor);
        return queries.students(examId, page, size);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page >= 0, 1 <= size <= 100, and offset <= 2147483647");
    }
}
