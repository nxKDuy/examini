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
    @PostMapping("/{id}/internal/start-admissions") StartReservation reserveStart(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    @PostMapping("/{examId}/internal/start-admissions/{admissionId}/claim") ScheduleAdmission claimStart(@PathVariable UUID examId,@PathVariable UUID admissionId,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    @PostMapping("/{examId}/internal/start-admissions/{admissionId}/confirm") void confirmStart(@PathVariable UUID examId,@PathVariable UUID admissionId,@RequestParam UUID sessionId,@RequestHeader("X-Service-Token")String token);
    @PostMapping("/{examId}/internal/start-admissions/{admissionId}/release") void releaseStart(@PathVariable UUID examId,@PathVariable UUID admissionId,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/attempt-limit") Integer attemptLimit(@PathVariable UUID id,@RequestParam UUID user,@RequestHeader("X-Service-Token")String token);
    @GetMapping("/{id}/internal/safe") List<Question> safe(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    /** The frozen questions with their keys; used only to check answers of practice attempts. */
    @GetMapping("/{id}/internal/grading") List<KeyQuestion> keys(@PathVariable UUID id,@RequestHeader("X-Service-Token")String token);
    record KeyQuestion(UUID questionId,String questionType,String content,List<KeyOption> options,String expectedAnswer,String explanation){}
    record KeyOption(UUID id,String content,int displayOrder,boolean correct){}
    @GetMapping("/{id}/internal/permission") String permission(@PathVariable UUID id,@RequestParam UUID actor,@RequestParam boolean manage,@RequestHeader("X-Service-Token")String token);
    record ExamDto(UUID id,String title,int durationMinutes,int maxConcurrentSessions,int attemptLimit,LocalDateTime endTime,boolean shuffleQuestions,boolean shuffleOptions,com.datn.examsession.dto.IntegrityPolicy integrityPolicy,String type){
        public ExamDto(UUID id,String title,int durationMinutes,int maxConcurrentSessions,int attemptLimit,LocalDateTime endTime,boolean shuffleQuestions,boolean shuffleOptions,com.datn.examsession.dto.IntegrityPolicy integrityPolicy){this(id,title,durationMinutes,maxConcurrentSessions,attemptLimit,endTime,shuffleQuestions,shuffleOptions,integrityPolicy,null);}
        public ExamDto(UUID id,String title,int durationMinutes,int maxConcurrentSessions,int attemptLimit,LocalDateTime endTime,boolean shuffleQuestions,boolean shuffleOptions){this(id,title,durationMinutes,maxConcurrentSessions,attemptLimit,endTime,shuffleQuestions,shuffleOptions,com.datn.examsession.dto.IntegrityPolicy.disabled());}
        public boolean practice(){return "PRACTICE".equals(type);}
    }
    /** presentation: media ids, reading passage and per-type limits from Question Bank, passed to the student as is. */
    record Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,int position,
                    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.util.Map<String,Object> presentation){
        public Question(UUID questionId,long questionVersion,String questionType,String content,List<Option> options,BigDecimal points,int position){
            this(questionId,questionVersion,questionType,content,options,points,position,null);
        }
        /** Questions under the same reading/listening passage share this key; null for a standalone question. */
        public String passageKey(){
            if(presentation==null || !(presentation.get("passage") instanceof java.util.Map<?,?> p))return null;
            Object k=p.get("key");return k==null?null:k.toString();
        }
        public Integer maxRecordSeconds(){
            Object v=presentation==null?null:presentation.get("maxRecordSeconds");return v instanceof Number n?n.intValue():null;
        }
    }
    record Option(UUID id,String content,int displayOrder){}
    record StartReservation(UUID examId,UUID admissionId,long scheduleRevision){}
    record ScheduleAdmission(UUID admissionId,long scheduleRevision,Instant startedAt,Instant deadlineAt){}
}
