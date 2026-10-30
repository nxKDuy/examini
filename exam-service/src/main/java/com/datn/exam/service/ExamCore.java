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
    private final ExamParticipantRepository participants;
    private final AttemptGrantRepository grants;
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
        if(participants.countByExamIdAndStatus(e.getId(),ExamParticipant.Status.ACTIVE)>p.maxParticipants()) throw error(409,"Existing participants exceed new limit");
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
    public void integrityPolicy(UUID id, UUID actor, com.datn.exam.dto.IntegrityPolicy policy) {
        Exam e=load(id);require(e,actor,true);draft(e);
        if(policy==null || policy.warningPolicy()==null || policy.warningEventLimit()==null ||
           policy.warningEventLimit()<com.datn.exam.dto.IntegrityPolicy.MIN_WARNING_EVENT_LIMIT ||
           policy.warningEventLimit()>com.datn.exam.dto.IntegrityPolicy.MAX_WARNING_EVENT_LIMIT)
            throw error(400,"warningEventLimit must be 1..100");
        e.setIntegrityPolicy(policy);
        audit(e,actor,"INTEGRITY_POLICY_UPDATED",policy.toString());
    }
    public IntegrityControl integrityControl(UUID id,UUID actor,boolean locked,String reason) {
        Exam e=load(id);String role=role(e,actor);
        if(!Set.of("OWNER","MANAGER","PROCTOR").contains(role))throw error(403,"Exam integrity control permission denied");
        if(internalToken.isBlank())throw error(503,"Session integration not configured");
        var result=locked?sessions.integrityLock(id,actor,new com.datn.exam.client.SessionClient.IntegrityLockRequest(reason),internalToken):sessions.integrityUnlock(id,actor,internalToken);
        boolean changed=e.isIntegrityLocked()!=locked;e.setIntegrityLocked(locked);
        if(changed)audit(e,actor,locked?"EXAM_INTEGRITY_LOCKED":"EXAM_INTEGRITY_UNLOCKED",
                "role="+role+" activeSessionsSubmitted="+result.activeSessionsSubmitted()+(reason==null?"":" reason="+reason));
        return new IntegrityControl(id,locked,!changed,result.activeSessionsSubmitted(),result.changedAt());
    }
    public record IntegrityControl(UUID examId,boolean locked,boolean unchanged,int activeSessionsSubmitted,Instant changedAt){}
    public void assign(UUID id, UUID actor, UUID user, boolean remove) {
        Exam e=load(id);require(e,actor,true);
        if(user==null) throw error(400,"User required");
        if(remove) {revoke(e,actor,user,null);return;}
        if(addParticipant(e,actor,user)==AssignOutcome.CAPACITY_FULL)throw error(409,"Participant capacity full");
    }
    public enum AssignOutcome { ASSIGNED, REINSTATED, ALREADY_PARTICIPANT, CAPACITY_FULL }
    /** Caller holds the Exam row lock and has verified OWNER/MANAGER. Capacity counts eligible (ACTIVE) participants only. */
    public AssignOutcome addParticipant(Exam e,UUID actor,UUID user) {
        if(e.getStatus()!=Exam.Status.DRAFT && e.getStatus()!=Exam.Status.PUBLISHED && e.getStatus()!=Exam.Status.OPEN)
            throw error(409,"PARTICIPANTS_LOCKED_FOR_CLOSED_EXAM");
        var row=participants.findByExamIdAndUserId(e.getId(),user).orElse(null);
        if(row!=null && row.isActive())return AssignOutcome.ALREADY_PARTICIPANT;
        if(participants.countByExamIdAndStatus(e.getId(),ExamParticipant.Status.ACTIVE)>=e.getMaxParticipants())return AssignOutcome.CAPACITY_FULL;
        Instant now=clock.instant();AssignOutcome outcome;
        if(row==null){row=new ExamParticipant(e.getId(),user,"ASSIGNED",actor,now);outcome=AssignOutcome.ASSIGNED;}
        else {row.reinstate(actor,now);outcome=AssignOutcome.REINSTATED;}
        participants.saveAndFlush(row);
        audit(e,actor,outcome==AssignOutcome.ASSIGNED?"PARTICIPANT_ASSIGNED":"PARTICIPANT_REINSTATED",user.toString());
        recipientChanged(e,"ASSIGNED",actor);
        events.participantChanged(e,user,outcome.name(),actor,null,null,counts(e.getId()));
        return outcome;
    }
    /** Revocation is a state: Join/Start are blocked, history and any active attempt are kept. Idempotent. */
    public void revoke(Exam e,UUID actor,UUID user,String reason) {
        var row=participants.findByExamIdAndUserId(e.getId(),user).orElseThrow(()->error(404,"PARTICIPANT_NOT_FOUND"));
        if(!row.isActive())return;
        String publicReason=reason==null||reason.isBlank()?null:reason.strip();
        if(publicReason!=null&&publicReason.length()>1000)throw error(400,"Revocation reason too long");
        row.revoke(actor,clock.instant(),publicReason);participants.saveAndFlush(row);
        audit(e,actor,"PARTICIPANT_REVOKED",user+(publicReason==null?"":" reason="+publicReason));
        recipientChanged(e,"UNASSIGNED",actor);
        events.participantChanged(e,user,"REVOKED",actor,null,publicReason,counts(e.getId()));
    }
    public record GrantResult(ExamParticipant participant,AttemptGrant grant,boolean replayed){}
    /** Positive per-Student grant: public reason mandatory, idempotent on requestId, never touches the Exam limit, schedule or capacity. */
    public GrantResult grantAttempts(Exam e,UUID actor,UUID user,Integer quantity,String reason,String requestId) {
        int qty=quantity==null?1:quantity;
        if(qty<1||qty>100)throw error(400,"Grant quantity must be between 1 and 100");
        String publicReason=reason==null?"":reason.strip();
        if(publicReason.isEmpty())throw error(400,"Public grant reason required");
        if(publicReason.length()>1000)throw error(400,"Grant reason too long");
        String key=requestId==null?"":requestId.strip();
        if(key.length()<8||key.length()>100)throw error(400,"requestId (8-100 characters) required for idempotent grants");
        var row=participants.findByExamIdAndUserId(e.getId(),user).orElseThrow(()->error(404,"PARTICIPANT_NOT_FOUND"));
        var existing=grants.findByExamIdAndUserIdAndRequestId(e.getId(),user,key);
        if(existing.isPresent()) {
            var g=existing.get();
            if(g.getQuantity()!=qty||!g.getReason().equals(publicReason))throw error(409,"IDEMPOTENCY_CONFLICT");
            return new GrantResult(row,g,true);
        }
        if(!row.isActive())throw error(409,"PARTICIPANT_REVOKED");
        try{row.addExtraAttempts(qty);}catch(ArithmeticException overflow){throw error(409,"Grant total too large");}
        var grant=grants.saveAndFlush(new AttemptGrant(e.getId(),user,qty,publicReason,actor,key,clock.instant()));
        participants.saveAndFlush(row);
        audit(e,actor,"ATTEMPT_GRANTED",user+" +"+qty+" reason="+publicReason);
        events.participantChanged(e,user,"ATTEMPT_GRANTED",actor,qty,publicReason,counts(e.getId()));
        return new GrantResult(row,grant,false);
    }
    public ParticipantCounts counts(UUID examId) {
        long active=0,revoked=0;
        for(Object[] r:participants.countsByStatus(examId)) {if(r[0]==ExamParticipant.Status.ACTIVE)active=(Long)r[1];else revoked=(Long)r[1];}
        return new ParticipantCounts(active+revoked,active,revoked);
    }
    /** registered = every row ever created (history), eligible = ACTIVE rows that count against maxParticipants. */
    public record ParticipantCounts(long registered,long eligible,long revoked){}
    public int effectiveAttemptLimit(Exam e,UUID user) {
        return Math.addExact(e.getAttemptLimit(),participants.findByExamIdAndUserId(e.getId(),user).map(ExamParticipant::getExtraAttempts).orElse(0));
    }
    public Exam join(UUID id, UUID user, String code) {
        Exam e=load(id);if(user==null)throw error(401,"Authentication required");
        if(e.getStatus()!=Exam.Status.PUBLISHED && e.getStatus()!=Exam.Status.OPEN)throw error(409,"Exam is not available to join");
        Instant now=clock.instant();
        if(e.getStartAt()!=null && now.isBefore(e.getStartAt()))throw error(409,"Join is not available before startAt");
        if(e.getEndAt()!=null && !now.isBefore(e.getEndAt()))throw error(409,"Exam schedule has ended");
        var row=participants.findByExamIdAndUserId(e.getId(),user).orElse(null);
        if(row!=null && !row.isActive()) throw error(403,"PARTICIPATION_REVOKED");
        if(e.getAccessMode()==Exam.AccessMode.ASSIGNED && row==null) throw error(403,"Not assigned");
        if(e.getAccessMode()==Exam.AccessMode.JOIN_CODE && row==null && !e.getCode().equals(code)) throw error(403,"Invalid join code");
        checkAttempts(e,user,row);
        if(row==null) {
            if(participants.countByExamIdAndStatus(e.getId(),ExamParticipant.Status.ACTIVE)>=e.getMaxParticipants()) throw error(409,"Participant capacity full");
            participants.saveAndFlush(new ExamParticipant(e.getId(),user,e.getAccessMode().name(),null,now));
            recipientChanged(e,"JOINED",user);
            events.participantChanged(e,user,"JOINED",user,null,null,counts(e.getId()));
        }
        return e;
    }
    private void checkAttempts(Exam e,UUID user,ExamParticipant row){
        if(internalToken.isBlank())throw error(503,"Session integration not configured");
        var eligibility=sessions.eligibility(e.getId(),user,internalToken);
        long limit=(long)e.getAttemptLimit()+(row==null?0:row.getExtraAttempts());
        if(!eligibility.resumable() && eligibility.attempts()>=limit)throw error(409,"Attempt limit reached");
    }
    public Exam authorizeStart(UUID id, UUID user) {
        Exam e=load(id);startWindow(e);
        if(e.isIntegrityLocked())throw error(409,"EXAM_INTEGRITY_LOCKED");
        var row=participants.findByExamIdAndUserId(e.getId(),user).orElse(null);
        if(row==null) throw error(403,"Join required before Start");
        if(!row.isActive()) throw error(403,"PARTICIPATION_REVOKED");
        checkAttempts(e,user,row);return e;
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
            if(q==null || !Boolean.TRUE.equals(q.eligible()) || q.questionVersion()==null || q.questionVersion()<1)
                throw error(409,"Question is missing, ineligible or unversioned: "+id);
            if(points==null || points.signum()<=0)throw error(400,"Positive points required");
            if(q.content()==null || q.content().isBlank() || !Set.of("MULTIPLE_CHOICE","TRUE_FALSE","SHORT_ANSWER","ESSAY","SPEAKING","MULTIPLE_RESPONSE","TRUE_FALSE_GROUP").contains(q.questionType()))
                throw error(409,"Incomplete snapshot");
            var options=q.options()==null?List.<Snapshot.Option>of():q.options();
            if(Set.of("MULTIPLE_CHOICE","TRUE_FALSE").contains(q.questionType()) &&
               (options.size()<2 || ("TRUE_FALSE".equals(q.questionType()) && options.size()!=2) || options.stream().filter(Snapshot.Option::correct).count()!=1 ||
                options.stream().anyMatch(o->o.id()==null || o.content()==null) || options.stream().map(Snapshot.Option::id).distinct().count()!=options.size()))
                throw error(409,"Invalid objective grading key");
            // Several correct options / statements judged one by one: at least 2 options with ids; MULTIPLE_RESPONSE needs a correct one.
            if(Set.of("MULTIPLE_RESPONSE","TRUE_FALSE_GROUP").contains(q.questionType()) &&
               (options.size()<2 || options.size()>26 || ("TRUE_FALSE_GROUP".equals(q.questionType()) && options.size()>8)
                || ("MULTIPLE_RESPONSE".equals(q.questionType()) && options.stream().noneMatch(Snapshot.Option::correct))
                || options.stream().anyMatch(o->o.id()==null || o.content()==null) || options.stream().map(Snapshot.Option::id).distinct().count()!=options.size()))
                throw error(409,"Invalid objective grading key");
            if("SHORT_ANSWER".equals(q.questionType()) && (q.expectedAnswer()==null || q.expectedAnswer().isBlank()))throw error(409,"Missing short-answer key");
            snapshots.add(new Snapshot(id,q.questionVersion(),q.questionType(),q.content(),options,points,q.expectedAnswer(),q.explanation(),pos++,q.presentation()));
        }
        BigDecimal total=snapshots.stream().map(Snapshot::points).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(e.getPassingScore()!=null && (!Float.isFinite(e.getPassingScore()) || e.getPassingScore()<0 || e.getPassingScore()>total.floatValue()))throw error(400,"Invalid passing score");
        e.setSnapshots(snapshots);
    }
    public static ResponseStatusException error(int status,String message) { return new ResponseStatusException(HttpStatus.valueOf(status),message); }
}
