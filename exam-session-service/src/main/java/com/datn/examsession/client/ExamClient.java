package com.datn.examsession.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@FeignClient(name = "exam-service", path = "/exams")
public interface ExamClient {

    @GetMapping("/code/{code}")
    ExamDto getByCode(@PathVariable("code") String code);

    @GetMapping("/{id}")
    ExamDto getById(@PathVariable("id") UUID id);

    @GetMapping("/{id}/questions/shuffled")
    List<UUID> getShuffledQuestionsForStudent(
            @PathVariable("id") UUID examId,
            @RequestParam("studentId") UUID studentId);

    record ExamDto(
            UUID id,
            String code,
            String title,
            int durationMinutes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status
    ) {}
}
