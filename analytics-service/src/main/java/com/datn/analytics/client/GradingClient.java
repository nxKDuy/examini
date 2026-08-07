package com.datn.analytics.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;

@FeignClient(name = "grading-service", path = "/grades")
public interface GradingClient {

    @GetMapping("/exam/{examId}/stats")
    ExamStats getStatsByExam(@PathVariable("examId") UUID examId);

    record ExamStats(long totalParticipants, float averageScore, float maxScore, float minScore) {}
}
