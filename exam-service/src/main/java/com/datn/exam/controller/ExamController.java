package com.datn.exam.controller;
import com.datn.exam.service.*;
import com.datn.exam.entity.*;
import com.datn.exam.dto.*;
import com.datn.exam.repository.*;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;

@RestController @RequestMapping("/exams") @RequiredArgsConstructor @Transactional
public class ExamController {
    private final ExamService service; private final ExamCore core;
    private final ExamRepository exams; private final ExamAuditRepository audits;
    private final ScheduleService schedules; private final StartAdmissionService admissions;
    private final ExamEventPublisher events;
    @Value("${notification.internal-token:}") private String notificationToken="";
    private void teacher(String role) { if(!"TEACHER".equals(role))throw ExamCore.error(403,"Teacher required"); }
    @GetMapping public Page<ExamDto.Response> list(@RequestAttribute UUID actor,Pageable page){return service.getExamsByCreator(actor,page);}
    @PostMapping("/manual") public ExamDto.Response create(@RequestAttribute UUID actor,@RequestAttribute String actorRole,@Valid @RequestBody ExamDto.ManualCreateRequest req){teacher(actorRole);return service.createManual(req,actor);}
    @PostMapping("/auto") public ExamDto.Response auto(@RequestAttribute UUID actor,@RequestAttribute String actorRole,@Valid @RequestBody ExamDto.AutoGenerateRequest req){teacher(actorRole);return service.autoGenerate(req,actor);}
    @GetMapping("/{id}") public ExamDto.Response detail(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,false);return ExamDto.Response.from(e);}
    @PutMapping("/{id}") public ExamDto.Response update(@PathVariable UUID id,@RequestAttribute UUID actor,@Valid @RequestBody ExamDto.ManualCreateRequest req){
        var e=core.load(id);core.require(e,actor,true);core.draft(e);
        var oldStart=e.getStartAt();var oldEnd=e.getEndAt();
        var newStart=req.startTime()==null?null:req.startTime().toInstant(java.time.ZoneOffset.UTC);
        var newEnd=req.endTime()==null?null:req.endTime().toInstant(java.time.ZoneOffset.UTC);
        ScheduleRules.validate(newStart,newEnd,e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),e.getReminderOffsetsMinutes(),req.durationMinutes());
        if(newEnd!=null&&!newEnd.isAfter(java.time.Instant.now()))throw ExamCore.error(400,"Cannot publish or reschedule a schedule with endAt in the past");
        boolean scheduleChanged=!Objects.equals(oldStart,newStart)||!Objects.equals(oldEnd,newEnd);
        e.setTitle(req.title());e.setDescription(req.description());e.setDurationMinutes(req.durationMinutes());e.setStartTime(req.startTime());e.setEndTime(req.endTime());e.setStartAt(newStart);e.setEndAt(newEnd);e.setPassingScore(req.passingScore());e.setShuffleQuestions(req.shuffleQuestions());e.setShuffleOptions(req.shuffleOptions());
        e.getPoints().clear();var ids=new ArrayList<UUID>();
        for(var q:req.questions()){if(q.weight()<=0 || !Double.isFinite(q.weight()) || e.getPoints().put(q.questionId(),java.math.BigDecimal.valueOf(q.weight()))!=null)throw ExamCore.error(400,"Invalid points/composition");ids.add(q.questionId());}
        e.setQuestionIds(ids);core.audit(e,actor,"EXAM_UPDATED",null);
        if(scheduleChanged){e.setScheduleRevision(e.getScheduleRevision()+1);core.audit(e,actor,"EXAM_SCHEDULE_CHANGED","{\"oldStartAt\":"+(oldStart==null?"null":"\""+oldStart+"\"")+",\"oldEndAt\":"+(oldEnd==null?"null":"\""+oldEnd+"\"")+",\"newStartAt\":"+(newStart==null?"null":"\""+newStart+"\"")+",\"newEndAt\":"+(newEnd==null?"null":"\""+newEnd+"\"")+",\"revision\":"+e.getScheduleRevision()+"}");events.scheduleChanged(e,ScheduleService.changeType(oldStart,oldEnd,newStart,newEnd),actor);}
        return ExamDto.Response.from(e);
    }
    @PutMapping("/{id}/publish") public ExamDto.Response publish(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.transition(id,actor,Exam.Status.PUBLISHED));}
    @PutMapping("/{id}/open") public ExamDto.Response open(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.transition(id,actor,Exam.Status.OPEN));}
    @PutMapping("/{id}/close") public ExamDto.Response close(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody(required=false) LifecycleAction request){return ExamDto.Response.from(core.transition(id,actor,Exam.Status.CLOSED,request==null?null:request.reason(),"MANUAL"));}
    @PutMapping("/{id}/reopen") public ExamDto.Response reopen(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody(required=false) LifecycleAction request){return ExamDto.Response.from(core.reopen(id,actor,request==null?null:request.reason()));}
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,true);core.draft(e);service.delete(id);}
    public record Member(UUID userId,Exam.MemberRole role){}
    @PutMapping("/{id}/members") public void invite(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody Member m){core.member(id,actor,m.userId(),m.role(),false);}
    @DeleteMapping("/{id}/members/{user}") public void remove(@PathVariable UUID id,@PathVariable UUID user,@RequestAttribute UUID actor){core.member(id,actor,user,null,true);}
    @PostMapping("/{id}/members/accept") public void accept(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole){core.accept(id,actor,actorRole);}
    @GetMapping("/invitations") public List<ExamDto.Response> invitations(@RequestAttribute UUID actor){return exams.findAll().stream().filter(e->e.getInvitations().containsKey(actor)).map(ExamDto.Response::from).toList();}
    @GetMapping("/{id}/members") public Map<UUID,Exam.MemberRole> members(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,false);return e.getMembers();}
    @PutMapping("/{id}/policy") public void policy(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody ExamCore.Policy p){core.policy(id,actor,p);}
    @GetMapping("/{id}/schedule") public ScheduleDto.Response schedule(@PathVariable UUID id,@RequestAttribute UUID actor){return schedules.get(id,actor);}
    @PutMapping("/{id}/schedule") public ScheduleDto.Response schedule(@PathVariable UUID id,@RequestAttribute UUID actor,@Valid @RequestBody ScheduleDto.Update request){return schedules.update(id,actor,request);}
    @GetMapping("/{id}/result-policy") public ResultPolicyDto.Policy resultPolicy(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,true);return ResultPolicyDto.Policy.from(e);}
    @PutMapping("/{id}/result-policy") public ResultPolicyDto.Policy resultPolicy(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody ResultPolicyDto.Policy request){var e=core.load(id);core.require(e,actor,true);e.setResultAutoRelease(request.autoRelease());e.setResultShowScore(request.showScore());e.setResultShowQuestionBreakdown(request.showQuestionBreakdown());e.setResultShowFeedback(request.showFeedback());e.setResultShowCorrectAnswers(request.showCorrectAnswers());e.setResultShowExplanation(request.showExplanation());core.audit(e,actor,"RESULT_POLICY_UPDATED",null);return ResultPolicyDto.Policy.from(e);}
    @GetMapping("/{id}/internal/result-policy") public ResultPolicyDto.Internal internalResultPolicy(@PathVariable UUID id){var e=core.load(id);return new ResultPolicyDto.Internal(e.getStatus().name(),ResultPolicyDto.Policy.from(e));}
    @PutMapping("/{id}/participants/{user}") public void assign(@PathVariable UUID id,@PathVariable UUID user,@RequestAttribute UUID actor){core.assign(id,actor,user,false);}
    @DeleteMapping("/{id}/participants/{user}") public void unassign(@PathVariable UUID id,@PathVariable UUID user,@RequestAttribute UUID actor){core.assign(id,actor,user,true);}
    @GetMapping("/{id}/participants") public Map<UUID,String> participants(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,false);return e.getParticipants();}
    @GetMapping("/{id}/audit") public List<ExamAudit> audit(@PathVariable UUID id,@RequestAttribute UUID actor){core.require(core.load(id),actor,false);return audits.findByExamIdOrderByTimestampAsc(id);}
    @GetMapping("/public") public List<ExamDto.Response> publicExams(@RequestAttribute UUID actor){return exams.findByAccessModeAndStatusIn(Exam.AccessMode.PUBLIC,List.of(Exam.Status.PUBLISHED,Exam.Status.OPEN)).stream().map(ExamDto.Response::from).toList();}
    @GetMapping("/my") public List<ExamDto.Response> my(@RequestAttribute UUID actor){return schedules.myExams(actor).stream().map(ExamDto.Response::from).toList();}
    @GetMapping("/code/{code}") public ExamDto.Response byCode(@PathVariable String code,@RequestAttribute UUID actor){var e=exams.findByCode(code).orElseThrow(()->ExamCore.error(404,"Exam not found"));if(!e.getParticipants().containsKey(actor))core.require(e,actor,false);return ExamDto.Response.from(e);}
    @GetMapping("/{id}/questions/shuffled") public List<UUID> shuffled(@PathVariable UUID id,@RequestParam UUID studentId,@RequestAttribute UUID actor){var e=core.load(id);if(!actor.equals(studentId) || !e.getParticipants().containsKey(actor))core.require(e,actor,false);return service.getShuffledQuestionsForStudent(id,studentId);}
    @PostMapping("/code/{code}/join") public ExamDto.Response joinCode(@PathVariable String code,@RequestAttribute UUID actor){var e=exams.findByCode(code).orElseThrow(()->ExamCore.error(404,"Exam not found"));return ExamDto.Response.from(core.join(e.getId(),actor,code));}
    @PostMapping("/{id}/join") public ExamDto.Response join(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestParam(required=false)String code){return ExamDto.Response.from(core.join(id,actor,code));}
    @GetMapping("/{id}/lobby") public ExamDto.Response lobby(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);if(e.getStatus()==Exam.Status.DRAFT || !e.getParticipants().containsKey(actor))throw ExamCore.error(404,"EXAM_NOT_AVAILABLE");return ExamDto.Response.from(e);}
    @GetMapping("/{id}/availability") public ScheduleDto.Availability availability(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestParam(required=false)String code){return schedules.availability(id,actor,code);}
    @GetMapping("/code/{code}/preview") public ScheduleDto.Availability codePreview(@PathVariable String code,@RequestAttribute UUID actor){var e=exams.findByCode(code).orElseThrow(()->ExamCore.error(404,"Exam not found"));return schedules.availability(e.getId(),actor,code);}
    @GetMapping("/calendar") public Page<ScheduleDto.CalendarItem> calendar(@RequestAttribute UUID actor,@RequestAttribute String actorRole,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(defaultValue="false")boolean includeUnscheduled,Pageable pageable){
        if(pageable.getPageSize()>100)pageable=PageRequest.of(pageable.getPageNumber(),100,pageable.getSort());
        return schedules.calendar(actor,actorRole,from,to,includeUnscheduled,pageable);
    }
    @PutMapping("/{id}/follow") public ScheduleDto.FollowResponse follow(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole){if(!"STUDENT".equals(actorRole))throw ExamCore.error(403,"Student required");return schedules.follow(id,actor,true);}
    @DeleteMapping("/{id}/follow") public ScheduleDto.FollowResponse unfollow(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole){if(!"STUDENT".equals(actorRole))throw ExamCore.error(403,"Student required");return schedules.follow(id,actor,false);}
    @PostMapping("/{id}/preflight") public ExamDto.Response preflight(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.authorizeStart(id,actor));}
    @GetMapping("/{id}/internal/permission") public String permission(@PathVariable UUID id,@RequestParam UUID actor,@RequestParam(defaultValue="false") boolean manage){var e=core.load(id);core.require(e,actor,manage);return core.role(e,actor);}
    @PostMapping("/{id}/internal/start") public ExamDto.Response start(@PathVariable UUID id,@RequestParam UUID user){return ExamDto.Response.from(core.authorizeStart(id,user));}
    @PostMapping("/{id}/internal/start-admissions") public ScheduleDto.Reservation reserveStart(@PathVariable UUID id,@RequestParam UUID user){return admissions.reserve(id,user);}
    @PostMapping("/{examId}/internal/start-admissions/{admissionId}/claim") public ScheduleDto.Admission claimStart(@PathVariable UUID examId,@PathVariable UUID admissionId,@RequestParam UUID user){return admissions.claim(examId,admissionId,user);}
    @PostMapping("/{examId}/internal/start-admissions/{admissionId}/confirm") public void confirmStart(@PathVariable UUID examId,@PathVariable UUID admissionId,@RequestParam UUID sessionId){admissions.confirm(examId,admissionId,sessionId);}
    @PostMapping("/{examId}/internal/start-admissions/{admissionId}/release") public void releaseStart(@PathVariable UUID examId,@PathVariable UUID admissionId){admissions.release(examId,admissionId);}
    @GetMapping("/{id}/internal/notification-recipients") public ScheduleDto.RecipientSnapshot notificationRecipients(@PathVariable UUID id,@RequestHeader("X-Service-Token")String serviceToken){return schedules.notificationRecipients(id,serviceToken,notificationToken);}
    @GetMapping("/{id}/internal/safe") public List<Snapshot.Safe> safe(@PathVariable UUID id){return core.load(id).getSnapshots().stream().map(Snapshot::safe).toList();}
    @GetMapping("/{id}/internal/grading") public List<Snapshot> grading(@PathVariable UUID id){return core.load(id).getSnapshots();}
    public record LifecycleAction(String reason){}
}
