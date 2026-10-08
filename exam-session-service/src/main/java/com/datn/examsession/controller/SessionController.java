package com.datn.examsession.controller;
import com.datn.examsession.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/sessions") @RequiredArgsConstructor
public class SessionController {
    private final SessionService service;private final com.datn.examsession.client.ExamClient exams;
    @PostMapping("/start") public SessionService.StartSessionResponse start(@RequestParam(required=false) UUID examId,@RequestParam(required=false) String examCode,@RequestHeader("Authorization") String bearer,@RequestAttribute UUID actor){if(examId==null){if(examCode==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Exam identity required");examId=exams.byCode(examCode,bearer).id();}return service.startExam(examId,actor);}
    @GetMapping("/{id}") public SessionService.StartSessionResponse get(@PathVariable UUID id,@RequestAttribute UUID actor){return service.get(id,actor);}
    @PostMapping("/{id}/answers") public void answer(@PathVariable UUID id,@RequestParam UUID questionId,@RequestAttribute UUID actor,@RequestBody Answer req){service.saveAnswer(id,actor,questionId,req.content());}
    @PostMapping("/{id}/submit") public void submit(@PathVariable UUID id,@RequestAttribute UUID actor){service.submitExam(id,actor);}
    @GetMapping("/exam/{id}/monitor") public List<SessionService.Monitor> monitor(@PathVariable UUID id,@RequestAttribute UUID actor){return service.monitor(id,actor);}
    @GetMapping("/{id}/internal/answers") public SessionService.SessionDetailDto internal(@PathVariable UUID id){return service.getSessionDetails(id);}
    @GetMapping("/{id}/internal/owner") public UUID owner(@PathVariable UUID id){return service.owner(id);}
    @GetMapping("/exam/{id}/internal/eligibility") public Eligibility eligibility(@PathVariable UUID id,@RequestParam UUID user){return new Eligibility(service.attempts(id,user),service.resumable(id,user));}
    @GetMapping("/exam/{id}/internal/started") public boolean started(@PathVariable UUID id){return service.hasStarted(id);}
    @GetMapping("/exam/{id}/internal/availability") public SessionService.ScheduleAvailability availability(@PathVariable UUID id,@RequestParam UUID user){return service.availability(id,user);}
    @GetMapping("/exam/{id}/internal/recipient-progress") public List<SessionService.RecipientProgress> recipientProgress(@PathVariable UUID id,@RequestParam List<UUID> userIds){return service.recipientProgress(id,userIds);}
    @GetMapping("/exam/{examId}/internal/start-admissions/{admissionId}") public SessionService.AdmissionStatus admissionStatus(@PathVariable UUID examId,@PathVariable UUID admissionId){return service.admissionStatus(examId,admissionId);}
    @GetMapping("/exam/{id}/mine") public List<SessionService.AttemptDto> mine(@PathVariable UUID id,@RequestAttribute UUID actor){return service.myAttempts(id,actor);}
    @GetMapping("/exam/{id}/attempts") public List<SessionService.StaffAttemptDto> attempts(@PathVariable UUID id,@RequestParam UUID userId,@RequestAttribute UUID actor){return service.staffAttempts(id,userId,actor);}
    @PostMapping("/{id}/invalidate") public SessionService.StaffAttemptDto invalidate(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody InvalidateRequest req){return service.invalidate(id,actor,req==null?null:req.reason());}
    @GetMapping("/exam/{id}/internal/attempt-summary") public List<SessionService.AttemptSummary> attemptSummary(@PathVariable UUID id,@RequestParam List<UUID> userIds){return service.attemptSummary(id,userIds);}
    @GetMapping("/exam/{id}/internal/attempts") public List<SessionService.AttemptDto> internalAttempts(@PathVariable UUID id,@RequestParam UUID user){return service.myAttempts(id,user);}
    public record InvalidateRequest(String reason){}
    public record Eligibility(long attempts,boolean resumable){}
    public record Answer(String content){}
}
