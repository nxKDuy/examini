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

@RestController @RequestMapping("/exams") @RequiredArgsConstructor @Transactional
public class ExamController {
    private final ExamService service; private final ExamCore core;
    private final ExamRepository exams; private final ExamAuditRepository audits;
    private void teacher(String role) { if(!Set.of("TEACHER","ADMIN").contains(role))throw ExamCore.error(403,"Teacher required"); }
    @GetMapping public Page<ExamDto.Response> list(@RequestAttribute UUID actor,Pageable page){return service.getExamsByCreator(actor,page);}
    @PostMapping("/manual") public ExamDto.Response create(@RequestAttribute UUID actor,@RequestAttribute String actorRole,@Valid @RequestBody ExamDto.ManualCreateRequest req){teacher(actorRole);return service.createManual(req,actor);}
    @PostMapping("/auto") public ExamDto.Response auto(@RequestAttribute UUID actor,@RequestAttribute String actorRole,@Valid @RequestBody ExamDto.AutoGenerateRequest req){teacher(actorRole);return service.autoGenerate(req,actor);}
    @GetMapping("/{id}") public ExamDto.Response detail(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,false);return ExamDto.Response.from(e);}
    @PutMapping("/{id}") public ExamDto.Response update(@PathVariable UUID id,@RequestAttribute UUID actor,@Valid @RequestBody ExamDto.ManualCreateRequest req){
        var e=core.load(id);core.require(e,actor,true);core.draft(e);
        e.setTitle(req.title());e.setDescription(req.description());e.setDurationMinutes(req.durationMinutes());e.setStartTime(req.startTime());e.setEndTime(req.endTime());e.setPassingScore(req.passingScore());e.setShuffleQuestions(req.shuffleQuestions());e.setShuffleOptions(req.shuffleOptions());
        e.getPoints().clear();var ids=new ArrayList<UUID>();
        for(var q:req.questions()){if(q.weight()<=0 || !Double.isFinite(q.weight()) || e.getPoints().put(q.questionId(),java.math.BigDecimal.valueOf(q.weight()))!=null)throw ExamCore.error(400,"Invalid points/composition");ids.add(q.questionId());}
        e.setQuestionIds(ids);core.audit(e,actor,"EXAM_UPDATED",null);return ExamDto.Response.from(e);
    }
    @PutMapping("/{id}/publish") public ExamDto.Response publish(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.transition(id,actor,Exam.Status.PUBLISHED));}
    @PutMapping("/{id}/open") public ExamDto.Response open(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.transition(id,actor,Exam.Status.OPEN));}
    @PutMapping("/{id}/close") public ExamDto.Response close(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.transition(id,actor,Exam.Status.CLOSED));}
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,true);core.draft(e);service.delete(id);}
    public record Member(UUID userId,Exam.MemberRole role){}
    @PutMapping("/{id}/members") public void invite(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody Member m){core.member(id,actor,m.userId(),m.role(),false);}
    @DeleteMapping("/{id}/members/{user}") public void remove(@PathVariable UUID id,@PathVariable UUID user,@RequestAttribute UUID actor){core.member(id,actor,user,null,true);}
    @PostMapping("/{id}/members/accept") public void accept(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestAttribute String actorRole){core.accept(id,actor,actorRole);}
    @GetMapping("/invitations") public List<ExamDto.Response> invitations(@RequestAttribute UUID actor){return exams.findAll().stream().filter(e->e.getInvitations().containsKey(actor)).map(ExamDto.Response::from).toList();}
    @GetMapping("/{id}/members") public Map<UUID,Exam.MemberRole> members(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,false);return e.getMembers();}
    @PutMapping("/{id}/policy") public void policy(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestBody ExamCore.Policy p){core.policy(id,actor,p);}
    @PutMapping("/{id}/participants/{user}") public void assign(@PathVariable UUID id,@PathVariable UUID user,@RequestAttribute UUID actor){core.assign(id,actor,user,false);}
    @DeleteMapping("/{id}/participants/{user}") public void unassign(@PathVariable UUID id,@PathVariable UUID user,@RequestAttribute UUID actor){core.assign(id,actor,user,true);}
    @GetMapping("/{id}/participants") public Map<UUID,String> participants(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);core.require(e,actor,false);return e.getParticipants();}
    @GetMapping("/{id}/audit") public List<ExamAudit> audit(@PathVariable UUID id,@RequestAttribute UUID actor){core.require(core.load(id),actor,false);return audits.findByExamIdOrderByTimestampAsc(id);}
    @GetMapping("/public") public List<ExamDto.Response> publicExams(){return exams.findByAccessModeAndStatus(Exam.AccessMode.PUBLIC,Exam.Status.OPEN).stream().map(ExamDto.Response::from).toList();}
    @GetMapping("/my") public List<ExamDto.Response> my(@RequestAttribute UUID actor){return exams.findAssigned(actor).stream().map(ExamDto.Response::from).toList();}
    @GetMapping("/code/{code}") public ExamDto.Response byCode(@PathVariable String code,@RequestAttribute UUID actor){var e=exams.findByCode(code).orElseThrow(()->ExamCore.error(404,"Exam not found"));if(!e.getParticipants().containsKey(actor))core.require(e,actor,false);return ExamDto.Response.from(e);}
    @GetMapping("/{id}/questions/shuffled") public List<UUID> shuffled(@PathVariable UUID id,@RequestParam UUID studentId,@RequestAttribute UUID actor){var e=core.load(id);if(!actor.equals(studentId) || !e.getParticipants().containsKey(actor))core.require(e,actor,false);return service.getShuffledQuestionsForStudent(id,studentId);}
    @PostMapping("/code/{code}/join") public ExamDto.Response joinCode(@PathVariable String code,@RequestAttribute UUID actor){var e=exams.findByCode(code).orElseThrow(()->ExamCore.error(404,"Exam not found"));return ExamDto.Response.from(core.join(e.getId(),actor,code));}
    @PostMapping("/{id}/join") public ExamDto.Response join(@PathVariable UUID id,@RequestAttribute UUID actor,@RequestParam(required=false)String code){return ExamDto.Response.from(core.join(id,actor,code));}
    @GetMapping("/{id}/lobby") public ExamDto.Response lobby(@PathVariable UUID id,@RequestAttribute UUID actor){var e=core.load(id);if(!e.getParticipants().containsKey(actor))throw ExamCore.error(403,"Join required");return ExamDto.Response.from(e);}
    @PostMapping("/{id}/preflight") public ExamDto.Response preflight(@PathVariable UUID id,@RequestAttribute UUID actor){return ExamDto.Response.from(core.authorizeStart(id,actor));}
    @GetMapping("/{id}/internal/permission") public String permission(@PathVariable UUID id,@RequestParam UUID actor,@RequestParam(defaultValue="false") boolean manage){var e=core.load(id);core.require(e,actor,manage);return core.role(e,actor);}
    @PostMapping("/{id}/internal/start") public ExamDto.Response start(@PathVariable UUID id,@RequestParam UUID user){return ExamDto.Response.from(core.authorizeStart(id,user));}
    @GetMapping("/{id}/internal/safe") public List<Snapshot.Safe> safe(@PathVariable UUID id){return core.load(id).getSnapshots().stream().map(Snapshot::safe).toList();}
    @GetMapping("/{id}/internal/grading") public List<Snapshot> grading(@PathVariable UUID id){return core.load(id).getSnapshots();}
}
