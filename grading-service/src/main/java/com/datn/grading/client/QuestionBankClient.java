package com.datn.grading.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "question-bank-service", path = "/questions")
public interface QuestionBankClient {

    @PostMapping("/batch")
    List<QuestionDetailDto> getQuestionsWithAnswers(@RequestBody List<UUID> ids);

    record QuestionDetailDto(
            UUID id,
            String content,
            String questionType,
            List<OptionDto> options
    ) {}

    record OptionDto(
            UUID id,
            String content,
            boolean correct
    ) {}
}
