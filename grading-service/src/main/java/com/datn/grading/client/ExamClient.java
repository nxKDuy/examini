package com.datn.grading.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.math.BigDecimal;
@FeignClient(name="exam-service",url="${exam-service.url:}",path="/exams")
public interface ExamClient {
    @GetMapping("/{id}/internal/grading") List<Question> key(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/permission") String permission(@PathVariable UUID id,@RequestParam UUID actor,@RequestParam boolean manage,@RequestHeader("X-Service-Token")String token);
    record Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,String expectedAnswer,int position){}
    record Option(UUID id,String content,int displayOrder,boolean correct){}
}
