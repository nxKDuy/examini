package com.datn.grading.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "exam-session-service", path = "/sessions")
public interface ExamSessionClient {

    @GetMapping("/{sessionId}/internal/answers")
    SessionDetailDto getSessionDetails(@PathVariable("sessionId") UUID sessionId);

    record SessionDetailDto(
            UUID examId,
            UUID userId,
            List<AnswerDto> answers
    ) {}

    record AnswerDto(
            UUID questionId,
            String answerContent
    ) {}
}
