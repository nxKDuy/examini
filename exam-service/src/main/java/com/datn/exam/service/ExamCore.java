package com.datn.exam.service;
import com.datn.exam.entity.*;
import com.datn.exam.dto.Snapshot;
import com.datn.exam.repository.*;
import com.datn.exam.client.QuestionBankClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.math.BigDecimal;
import java.time.*;

@Service @RequiredArgsConstructor @Transactional
public class ExamCore {
    private final ExamRepository exams;
    private final ExamAuditRepository audits;
    private final StartAdmissionRepository admissions;
    private final QuestionBankClient questions;
    private final com.datn.exam.client.SessionClient sessions;
    private final ExamEventPublisher events;
    private final java.time.Clock clock;
    @Value("${exam.internal-token:}") private String internalToken="";
    @Value("${exam.capacity.platform-limit:100}") private int platformLimit = 100;
    @Value("${question-bank.internal-token:}") private String questionToken = "";
    public Exam load(UUID id) { return exams.lockById(id).orElseThrow(() -> error(404,"Exam not found")); }
    public String role(Exam e, UUID actor) {
        if (actor == null) throw error(401,"Authentication required");
        if (actor.equals(e.getOwnerUserId())) return "OWNER";
        var role=e.getMembers().get(actor); return role == null ? "NONE" : role.name();
    }
    public void require(Exam e, UUID actor, boolean manage) {
        String r=role(e,actor);
        if (!(r.equals("OWNER") || r.equals("MANAGER") || (!manage && r.equals("PROCTOR"))))
            throw error(403,"Exam permission denied");
    }
    public void draft(Exam e) { if(e.getStatus()!=Exam.Status.DRAFT) throw error(409,"Only DRAFT is editable"); }
    public void audit(Exam e, UUID actor, String action, String metadata) {
        audits.save(new ExamAudit(e.getId(), actor, action, metadata));
    }
    private void recipientChanged(Exam e,String cause,UUID actor) {
        e.setRecipientRevision(e.getRecipientRevision()+1);
        events.recipientsChanged(e,cause,actor);
    }
    public void member(UUID id, UUID actor, UUID user, Exam.MemberRole role, boolean remove) {
        Exam e=load(id);
        if (!role(e,actor).equals("OWNER")) throw error(403,"Only owner manages collaborators");
        if (user == null || user.equals(e.getOwnerUserId())) throw error(400,"Owner cannot be a member");
        boolean wasManager=Exam.MemberRole.MANAGER==e.getMembers().get(user);
        var old=e.getMembers().getOrDefault(user,e.getInvitations().get(user));
        if(remove) { if(old == null) return; e.getMembers().remove(user); e.getInvitations().remove(user); }
        else { if(role == null) throw error(400,"Member role required"); if(old != null) throw error(409,"Member already exists; remove first"); e.getInvitations().put(user,role); }
        audit(e,actor,(remove?old:role).name()+(remove?"_REMOVED":"_INVITED"),user.toString());
        if(remove && wasManager)recipientChanged(e,"MEMBER_REMOVED",actor);
    }
    public void accept(UUID id, UUID actor, String actorRole) {
        if(!"TEACHER".equals(actorRole))throw error(403,"Teacher required");
        Exam e=load(id);var invited=e.getInvitations().remove(actor);
        if(invited==null)throw error(404,"No invitation");
        e.getMembers().put(actor,invited);audit(e,actor,invited.name()+"_ADDED","Invitation accepted");
        if(invited==Exam.MemberRole.MANAGER)recipientChanged(e,"MEMBER_ADDED",actor);
    }
    public record Policy(Exam.AccessMode accessMode, int maxParticipants, int maxConcurrentSessions, int attemptLimit,String joinCode) {
        public Policy(Exam.AccessMode mode,int participants,int concurrent,int attempts){this(mode,participants,concurrent,attempts,null);}
    }
    public void policy(UUID id, UUID actor, Policy p) {
        Exam e=load(id);require(e,actor,true);draft(e);
        validatePolicy(p);
        if(e.getParticipants().size()>p.maxParticipants()) throw error(409,"Existing participants exceed new limit");
        if(p.joinCode()!=null){if(!p.joinCode().matches("[A-Za-z0-9_-]{4,50}"))throw error(400,"Join code must be 4 to 50 letters/digits/_/-");if(!p.joinCode().equals(e.getCode()) && exams.existsByCode(p.joinCode()))throw error(409,"Join code already used");e.setCode(p.joinCode());}
        e.setAccessMode(p.accessMode());e.setMaxParticipants(p.maxParticipants());
        e.setMaxConcurrentSessions(p.maxConcurrentSessions());e.setAttemptLimit(p.attemptLimit());
        audit(e,actor,"PARTICIPATION_POLICY_UPDATED",p.toString());
    }
    private void validatePolicy(Policy p) {
        if(p==null || p.accessMode()==null || p.maxParticipants()<1 || p.attemptLimit()<1 ||
           p.maxConcurrentSessions()<1 || p.maxConcurrentSessions()>platformLimit || p.maxConcurrentSessions()>p.maxParticipants())
            throw error(400,"Invalid policy/capacity; platform limit "+platformLimit);
    }
    public void assign(UUID id, UUID actor, UUID user, boolean remove) {
        Exam e=load(id);require(e,actor,true);draft(e);
        if(user==null) throw error(400,"User required");
        boolean existed=e.getParticipants().containsKey(user);
        if(remove) {if(!existed)return;e.getParticipants().remove(user);} else {if(existed)return;register(e,user,"ASSIGNED");}
        audit(e,actor,remove?"PARTICIPANT_REMOVED":"PARTICIPANT_ASSIGNED",user.toString());
        recipientChanged(e,remove?"UNASSIGNED":"ASSIGNED",actor);
    }
    private void register(Exam e, UUID user, String source) {
        if(e.getParticipants().containsKey(user)) return;
        if(e.getParticipants().size()>=e.getMaxParticipants()) throw error(409,"Participant capacity full");
        e.getParticipants().put(user,source);
    }
    public Exam join(UUID id, UUID user, String code) {
        Exam e=load(id);if(user==null)throw error(401,"Authentication required");
        if(e.getStatus()!=Exam.Status.PUBLISHED && e.getStatus()!=Exam.Status.OPEN)throw error(409,"Exam is not available to join");
        Instant now=clock.instant();
        if(e.getStartAt()!=null && now.isBefore(e.getStartAt()))throw error(409,"Join is not available before startAt");
        if(e.getEndAt()!=null && !now.isBefore(e.getEndAt()))throw error(409,"Exam schedule has ended");
        if(e.getAccessMode()==Exam.AccessMode.ASSIGNED && !e.getParticipants().containsKey(user)) throw error(403,"Not assigned");
        if(e.getAccessMode()==Exam.AccessMode.JOIN_CODE && !e.getCode().equals(code)) throw error(403,"Invalid join code");
        checkAttempts(e,user);
        if(!e.getParticipants().containsKey(user)) {
            register(e,user,e.getAccessMode().name());
            recipientChanged(e,"JOINED",user);
        }
        return e;
    }
    private void checkAttempts(Exam e,UUID user){
        if(internalToken.isBlank())throw error(503,"Session integration not configured");
        var eligibility=sessions.eligibility(e.getId(),user,internalToken);
        if(!eligibility.resumable() && eligibility.attempts()>=e.getAttemptLimit())throw error(409,"Attempt limit reached");
    }
    public Exam authorizeStart(UUID id, UUID user) {
        Exam e=load(id);startWindow(e);
        if(!e.getParticipants().containsKey(user)) throw error(403,"Join required before Start");
        checkAttempts(e,user);return e;
    }
    public void startWindow(Exam e) {
        if(e.getStatus()!=Exam.Status.OPEN) throw error(409,"EXAM_NOT_OPEN");
        var now=clock.instant();
        if(e.getStartAt()!=null && now.isBefore(e.getStartAt())) throw error(409,"BEFORE_START_AT");
        if(e.getEndAt()!=null && !now.isBefore(e.getEndAt())) throw error(409,"AFTER_END_AT");
    }
    public Exam transition(UUID id, UUID actor, Exam.Status target) {
        return transition(id,actor,target,null,"MANUAL");
    }
    public Exam transition(UUID id,UUID actor,Exam.Status target,String reason,String trigger) {
        Exam e=load(id);if(!"AUTO".equals(trigger))require(e,actor,true);
        boolean valid=(e.getStatus()==Exam.Status.DRAFT && target==Exam.Status.PUBLISHED) ||
            (e.getStatus()==Exam.Status.PUBLISHED && target==Exam.Status.OPEN) ||
            ((e.getStatus()==Exam.Status.OPEN || e.getStatus()==Exam.Status.PUBLISHED) && target==Exam.Status.CLOSED);
        if(!valid)throw error(409,"Invalid lifecycle transition");
        if(target==Exam.Status.CLOSED && admissions.existsByExamIdAndStateIn(id,List.of(StartAdmission.State.PENDING,StartAdmission.State.CLAIMED)))
            throw error(409,"START_ADMISSION_IN_PROGRESS");
        if(target==Exam.Status.PUBLISHED) {
            ScheduleRules.validate(e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),e.getReminderOffsetsMinutes(),e.getDurationMinutes());
            publishSnapshot(e);
        }
        if(target==Exam.Status.OPEN && e.getSnapshots().isEmpty())throw error(409,"Legacy publication has no frozen snapshot; create a new exam");
        Instant now=clock.instant();
        if(target==Exam.Status.OPEN && e.getEndAt()!=null && !now.isBefore(e.getEndAt()))throw error(409,"Cannot open after endAt");
        if(target==Exam.Status.CLOSED && trigger.equals("MANUAL") && e.getEndAt()!=null && now.isBefore(e.getEndAt()) && (reason==null || reason.isBlank()))throw error(400,"Reason required for early close");
        e.setStatus(target);e.setLifecycleRevision(e.getLifecycleRevision()+1);
        String action=target==Exam.Status.OPEN?"EXAM_OPENED":target==Exam.Status.CLOSED?"EXAM_CLOSED":"EXAM_PUBLISHED";
        audit(e,actor,action,auditMetadata(trigger,reason,null,null,e.getLifecycleRevision()));
        events.lifecycleChanged(e,trigger,actor);
        return e;
    }
    public Exam reopen(UUID id,UUID actor,String reason) {
        Exam e=load(id);require(e,actor,true);
        if(e.getStatus()!=Exam.Status.CLOSED)throw error(409,"Only CLOSED exams can be reopened");
        if(e.getSnapshots().isEmpty())throw error(409,"Published snapshot unavailable");
        if(reason==null || reason.isBlank())throw error(400,"Reason required to reopen");
        if(e.getEndAt()!=null && !clock.instant().isBefore(e.getEndAt()))throw error(409,"Cannot reopen after endAt");
        e.setStatus(Exam.Status.OPEN);e.setLifecycleRevision(e.getLifecycleRevision()+1);
        audit(e,actor,"EXAM_REOPENED",auditMetadata("MANUAL",reason,null,null,e.getLifecycleRevision()));
        events.lifecycleChanged(e,"MANUAL",actor);return e;
    }
    public void automaticTransition(UUID id,Exam.Status target) {transition(id,null,target,null,"AUTO");}
    private String auditMetadata(String trigger,String reason,Object oldValue,Object newValue,long revision) {
        return "{\"trigger\":\""+trigger+"\",\"reason\":"+(reason==null?"null":"\""+reason.replace("\\","\\\\").replace("\"","\\\"")+"\"")+",\"old\":"+(oldValue==null?"null":"\""+oldValue+"\"") +",\"new\":"+(newValue==null?"null":"\""+newValue+"\"")+",\"revision\":"+revision+"}";
    }
    public void publishSnapshot(Exam e) {
        ScheduleRules.validate(e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),e.getReminderOffsetsMinutes(),e.getDurationMinutes());
        validatePolicy(new Policy(e.getAccessMode(),e.getMaxParticipants(),e.getMaxConcurrentSessions(),e.getAttemptLimit()));
        if(e.getDurationMinutes()<1 || e.getQuestionIds().isEmpty() || e.getQuestionIds().size()>200 ||
           new HashSet<>(e.getQuestionIds()).size()!=e.getQuestionIds().size() ||
           (e.getStartTime()!=null && e.getEndTime()!=null && !e.getStartTime().isBefore(e.getEndTime())) ||
           (e.getEndAt()!=null && !e.getEndAt().isAfter(clock.instant())))
            throw error(400,"Invalid exam composition/timing");
        if(questionToken.isBlank()) throw error(503,"Question Bank integration not configured");
        var data=questions.eligible(e.getQuestionIds(),questionToken,e.getOwnerUserId(),true,true);
        var byId=new HashMap<UUID,QuestionBankClient.Question>();
        for(var q:data) if(byId.put(q.id(),q)!=null) throw error(409,"Duplicate provider question");
        if(byId.size()!=e.getQuestionIds().size())throw error(409,"Unexpected provider question set");
        var snapshots=new ArrayList<Snapshot>();int pos=0;
        for(UUID id:e.getQuestionIds()) {
            var q=byId.get(id);BigDecimal points=e.getPoints().get(id);
            if(q==null || !"APPROVED".equals(q.status()) || q.questionVersion()==null || q.questionVersion()<1)
                throw error(409,"Question is missing, unapproved or unversioned: "+id);
            if(points==null || points.signum()<=0)throw error(400,"Positive points required");
            if(q.content()==null || q.content().isBlank() || !Set.of("MULTIPLE_CHOICE","TRUE_FALSE","SHORT_ANSWER","ESSAY").contains(q.questionType()))
                throw error(409,"Incomplete snapshot");
            var options=q.options()==null?List.<Snapshot.Option>of():q.options();
            if(Set.of("MULTIPLE_CHOICE","TRUE_FALSE").contains(q.questionType()) &&
               (options.size()<2 || ("TRUE_FALSE".equals(q.questionType()) && options.size()!=2) || options.stream().filter(Snapshot.Option::correct).count()!=1 ||
                options.stream().anyMatch(o->o.id()==null || o.content()==null) || options.stream().map(Snapshot.Option::id).distinct().count()!=options.size()))
                throw error(409,"Invalid objective grading key");
            if("SHORT_ANSWER".equals(q.questionType()) && (q.expectedAnswer()==null || q.expectedAnswer().isBlank()))throw error(409,"Missing short-answer key");
            snapshots.add(new Snapshot(id,q.questionVersion(),q.questionType(),q.content(),options,points,q.expectedAnswer(),pos++));
        }
        BigDecimal total=snapshots.stream().map(Snapshot::points).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(e.getPassingScore()!=null && (!Float.isFinite(e.getPassingScore()) || e.getPassingScore()<0 || e.getPassingScore()>total.floatValue()))throw error(400,"Invalid passing score");
        e.setSnapshots(snapshots);
    }
    public static ResponseStatusException error(int status,String message) { return new ResponseStatusException(HttpStatus.valueOf(status),message); }
}
