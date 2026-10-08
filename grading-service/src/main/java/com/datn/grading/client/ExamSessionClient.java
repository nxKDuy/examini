package com.datn.grading.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.LocalDateTime;
@FeignClient(name="exam-session-service",url="${exam-session.url:}",path="/sessions")
public interface ExamSessionClient {
    @GetMapping("/{id}/internal/answers") SessionDetailDto get(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    record SessionDetailDto(UUID sessionId,UUID examId,UUID userId,String status,String examTitle,int attemptNumber,LocalDateTime startedAt,LocalDateTime submittedAt,List<AnswerDto> answers,java.time.Instant invalidatedAt,String invalidationReason){
        public SessionDetailDto(UUID sessionId,UUID examId,UUID userId,String status,String examTitle,int attemptNumber,LocalDateTime startedAt,LocalDateTime submittedAt,List<AnswerDto> answers){this(sessionId,examId,userId,status,examTitle,attemptNumber,startedAt,submittedAt,answers,null,null);}
        public SessionDetailDto(UUID sessionId,UUID examId,UUID userId,String status,String examTitle,LocalDateTime startedAt,LocalDateTime submittedAt,List<AnswerDto> answers){this(sessionId,examId,userId,status,examTitle,1,startedAt,submittedAt,answers);}
    }
    record AnswerDto(UUID questionId,String answerContent){}
}
