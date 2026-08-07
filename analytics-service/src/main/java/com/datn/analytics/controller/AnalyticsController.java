package com.datn.analytics.controller;

import com.datn.analytics.client.GradingClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Báo cáo thống kê cho giáo viên")
public class AnalyticsController {

    private final GradingClient gradingClient;

    @GetMapping("/exam/{examId}")
    @Operation(summary = "Lấy báo cáo điểm thi của 1 đề")
    public GradingClient.ExamStats getExamAnalytics(@PathVariable UUID examId) {
        return gradingClient.getStatsByExam(examId);
    }
}
