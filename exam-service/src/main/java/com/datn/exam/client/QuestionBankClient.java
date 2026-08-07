package com.datn.exam.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Gọi sang question-bank-service để lấy danh sách question ID khi sinh đề tự động,
 * và lấy chi tiết câu hỏi khi thi.
 * ponytail: dùng tên service đăng ký trên Eureka "question-bank-service".
 */
@FeignClient(name = "question-bank-service", path = "/questions")
public interface QuestionBankClient {

    @GetMapping("/random")
    List<UUID> getRandomQuestionIds(
            @RequestParam("categoryId") UUID categoryId,
            @RequestParam("difficulty") String difficulty,
            @RequestParam("limit") int limit
    );
    
    // Khi cần thiết có thể thêm lấy chi tiết danh sách ID,
    // nhưng ở exam-service chỉ cần quản lý template (chứa ID)
}
