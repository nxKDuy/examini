package com.datn.examsession.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
@FeignClient(name="exam-service",url="${exam-service.url:}",path="/exams")
public interface ExamClient {
    @GetMapping("/code/{code}") ExamDto byCode(@PathVariable String code,@RequestHeader("Authorization")String bearer);
    @PostMapping("/{id}/internal/start") ExamDto authorize(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/safe") List<Question> safe(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/permission") String permission(@PathVariable UUID id,@RequestParam UUID actor,@RequestParam boolean manage,@RequestHeader("X-Service-Token")String token);
    record ExamDto(UUID id,String title,int durationMinutes,int maxConcurrentSessions,int attemptLimit,LocalDateTime endTime,boolean shuffleQuestions,boolean shuffleOptions){}
    record Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,int position){}
    record Option(UUID id,String content,int displayOrder){}
}
