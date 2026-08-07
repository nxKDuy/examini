package com.datn.examsession.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "question-bank-service", path = "/questions")
public interface QuestionBankClient {

    @PostMapping("/batch")
    List<QuestionResponse> getByIds(@RequestBody List<UUID> ids);

    record QuestionResponse(
            UUID id,
            String content,
            String questionType,
            List<OptionResponse> options
    ) {}

    record OptionResponse(
            UUID id,
            String content,
            int displayOrder
            // Không map correct vì đề phòng lộ đáp án xuống frontend
    ) {}
}
