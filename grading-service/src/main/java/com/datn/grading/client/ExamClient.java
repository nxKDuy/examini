package com.datn.grading.client;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.math.BigDecimal;
@FeignClient(name="exam-service",url="${exam-service.url:}",path="/exams")
public interface ExamClient {
    @GetMapping("/{id}/internal/grading") List<Question> key(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/permission") String permission(@PathVariable UUID id,@RequestParam UUID actor,@RequestParam boolean manage,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/result-policy") ResultPolicy resultPolicy(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/identity/{userId}") IdentityDisplay identity(@PathVariable UUID id,@PathVariable UUID userId,@RequestHeader("X-Service-Token")String token);
    record Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,String expectedAnswer,String explanation,int position,Map<String,Object> presentation){
        public Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,String expectedAnswer,String explanation,int position){this(questionId,questionVersion,questionType,content,options,points,expectedAnswer,explanation,position,null);}
        public Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,String expectedAnswer,int position){this(questionId,questionVersion,questionType,content,options,points,expectedAnswer,null,position);}
    }
    record Option(UUID id,String content,int displayOrder,boolean correct){}
    record IdentityDisplay(UUID userId,String displayName){}
    record Policy(boolean autoRelease,boolean showScore,boolean showQuestionBreakdown,boolean showFeedback,boolean showCorrectAnswers,boolean showExplanation){}
    record ResultPolicy(String examStatus,Policy policy){}
}
