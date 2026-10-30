package com.datn.examsession.service;
import com.datn.examsession.client.ExamClient;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.time.*;

@Service @RequiredArgsConstructor @Transactional
public class SessionService {
    private final SessionRepository sessions;private final SessionAnswerRepository answers;
    private final SubmissionEventRepository events;private final ExamClient exams;private final EntityManager em;
    private final AttemptEventRepository attemptEvents;private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final ExamIntegrityLockRepository integrityLocks;private final SessionIntegrityAuditRepository integrityAudits;
    @Value("${exam.internal-token:}") private String token="";
    @Value("${exam.capacity.platform-limit:100}") private int platformLimit=100;
    private static ResponseStatusException error(int code,String msg){return new ResponseStatusException(HttpStatus.valueOf(code),msg);}
    // PostgreSQL transaction lock serializes ALL starts for one exam, across processes.
    private void examLock(UUID exam) {em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(cast(:exam as text),0))").setParameter("exam",exam.toString()).getSingleResult();}
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public StartSessionResponse startExam(UUID examId,UUID user) {
        examLock(examId);
        if(integrityLocks.existsById(examId))throw error(409,"EXAM_INTEGRITY_LOCKED");
        var active=sessions.findActiveSession(examId,user);
        if(active.isPresent()) {
            var s=load(active.get().getId());expire(s);
            if(s.getStatus()==ExamSession.Status.IN_PROGRESS)return response(s);
        }
        for(var s:sessions.findByExamIdAndStatus(examId,ExamSession.Status.IN_PROGRESS))expire(load(s.getId()));
        if(token.isBlank())throw error(503,"Exam service integration not configured");
        var exam=exams.authorize(examId,user,token);
        var reservation=exams.reserveStart(examId,user,token);
        final UUID[] persistedSessionId={null};
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                if(persistedSessionId[0]!=null) {
                    try { exams.confirmStart(examId,reservation.admissionId(),persistedSessionId[0],token); }
                    catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(SessionService.class).warn("Start confirmation remains pending for admissionId={}",reservation.admissionId()); }
                } else releaseAdmission();
            }
            @Override public void afterCompletion(int status) { if(status==STATUS_ROLLED_BACK)releaseAdmission(); }
            private void releaseAdmission() {
                try { exams.releaseStart(examId,reservation.admissionId(),token); }
                catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(SessionService.class).warn("Start release remains pending for admissionId={}",reservation.admissionId()); }
            }
        });
        long attempts=sessions.countByExamIdAndUserId(examId,user);
        var granted=exams.attemptLimit(examId,user,token);
        // Grants only ever add attempts, so the effective limit can never be below the Exam limit.
        if(attempts>=(granted==null?exam.attemptLimit():Math.max(exam.attemptLimit(),granted)))throw error(409,"Attempt limit reached");
        int limit=Math.min(platformLimit,exam.maxConcurrentSessions());
        if(sessions.countByExamIdAndStatus(examId,ExamSession.Status.IN_PROGRESS)>=limit)
            throw error(409,"Kỳ thi hiện đã đạt giới hạn người đang làm bài. Vui lòng thử lại sau.");
        var questions=new ArrayList<>(exams.safe(examId,token));
        if(questions.isEmpty())throw error(409,"Published snapshot unavailable");
        if(exam.shuffleQuestions()){var mixed=shuffleKeepingPassages(questions,new Random());questions.clear();questions.addAll(mixed);}
        // True/false statements keep their a), b), c), d) order, like the printed exam.
        if(exam.shuffleOptions())for(int i=0;i<questions.size();i++){var q=questions.get(i);if("TRUE_FALSE_GROUP".equals(q.questionType()))continue;var options=new ArrayList<>(q.options());Collections.shuffle(options);questions.set(i,new ExamClient.Question(q.questionId(),q.questionVersion(),q.questionType(),q.content(),options,q.points(),q.position(),q.presentation()));}
        var admission=exams.claimStart(examId,reservation.admissionId(),user,token);
        Instant now=admission.startedAt();
        var s=ExamSession.builder().examId(examId).userId(user).startTime(LocalDateTime.ofInstant(now,ZoneOffset.UTC))
            .expiresAt(now.plusSeconds(exam.durationMinutes()*60L)).status(ExamSession.Status.IN_PROGRESS)
            .integrityPolicy(exam.integrityPolicy()==null?com.datn.examsession.dto.IntegrityPolicy.disabled():exam.integrityPolicy())
            .attemptNumber(Math.toIntExact(attempts+1)).questions(questions).examTitle(exam.title()).build();
        s.setExpiresAt(admission.deadlineAt());s.setScheduleRevision(admission.scheduleRevision());s.setStartAdmissionId(admission.admissionId());
        s.setAttemptNumber(nextAttemptNumber(examId,user)); // monotonic and never reused, independent of how many rows exist
        // Immutable snapshot order is persisted for this attempt and reused on refresh.
        sessions.saveAndFlush(s);persistedSessionId[0]=s.getId();
        em.refresh(s); // event carries the persisted (DB-precision) times so backfill/replay rebuilds the same payload
        attemptEvents.save(startedEvent(s));return response(s);
    }
    /** exam.attempt.started v1: one per successful Start; eventId is derived from sessionId so replay/backfill is idempotent. */
    public static UUID startedEventId(UUID sessionId){return UUID.nameUUIDFromBytes(("exam.attempt.started:v1:"+sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    AttemptEvent startedEvent(ExamSession s){
        Instant startedAt=s.getStartTime().toInstant(ZoneOffset.UTC);
        var payload=new AttemptStarted(s.getId(),s.getExamId(),s.getUserId(),s.getAttemptNumber(),startedAt,s.getExpiresAt());
        try{return AttemptEvent.publicOnly(startedEventId(s.getId()),"exam.attempt.started",s.getId(),json.writeValueAsString(payload),startedAt);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Cannot encode attempt event",e);}
    }
    /** Enqueues exam.attempt.started for sessions that predate the event; safe to run repeatedly. */
    @Transactional
    public int backfillStartedEvents(){
        int added=0;
        for(var s:sessions.findAll())if(s.getStartTime()!=null && !attemptEvents.existsById(startedEventId(s.getId()))){attemptEvents.save(startedEvent(s));added++;}
        return added;
    }
    public record AttemptStarted(UUID sessionId,UUID examId,UUID candidateId,int attemptNumber,Instant startedAt,Instant expiresAt){}
    /** Attempt numbers are monotonic per participant and never reused, independent of how many rows exist. */
    private int nextAttemptNumber(UUID examId,UUID user){return sessions.findTopByExamIdAndUserIdOrderByAttemptNumberDesc(examId,user).map(s->Math.addExact(s.getAttemptNumber(),1)).orElse(1);}
    public StartSessionResponse get(UUID id,UUID user) {var s=owned(id,user);expire(s);return response(s);}
    private ExamSession owned(UUID id,UUID user) {var s=load(id);if(!s.getUserId().equals(user))throw error(403,"Not your session");return s;}
    private ExamSession load(UUID id){var s=sessions.lockById(id).orElseThrow(()->error(404,"Session not found"));em.refresh(s);return s;}
    private void expire(ExamSession s){if(s.getStatus()==ExamSession.Status.IN_PROGRESS && !Instant.now().isBefore(s.getExpiresAt()))finish(s,ExamSession.Status.AUTO_SUBMITTED);}
    private void finish(ExamSession s,ExamSession.Status status){s.setStatus(status);s.setEndTime(LocalDateTime.now(ZoneOffset.UTC));events.save(new SubmissionEvent(s.getId()));}
    public boolean autoSubmitForIntegrity(ExamSession s,UUID triggerEventId,long eventCount) {
        if(s.getStatus()!=ExamSession.Status.IN_PROGRESS)return false;
        finish(s,ExamSession.Status.AUTO_SUBMITTED);Instant now=Instant.now();
        integrityAudits.save(new SessionIntegrityAudit(s,null,"SYSTEM","INTEGRITY_BUDGET_AUTO_SUBMITTED",triggerEventId,eventCount,now,"warningEventLimit="+s.getIntegrityPolicy().warningEventLimit()));
        return true;
    }
    public IntegrityLockResult lockIntegrityExam(UUID examId,UUID actor,String reason) {
        examLock(examId);boolean already=integrityLocks.existsById(examId);Instant now=Instant.now();
        if(!already)integrityLocks.save(new ExamIntegrityLock(examId,actor,now,reason));
        int submitted=0;
        for(var row:sessions.findByExamIdAndStatus(examId,ExamSession.Status.IN_PROGRESS)) {
            var s=load(row.getId());expire(s);
            if(s.getStatus()==ExamSession.Status.IN_PROGRESS) {
                finish(s,ExamSession.Status.AUTO_SUBMITTED);submitted++;
                integrityAudits.save(new SessionIntegrityAudit(s,actor,"EXAM_STAFF","EXAM_INTEGRITY_LOCK_AUTO_SUBMITTED",null,null,now,reason));
            }
        }
        return new IntegrityLockResult(examId,true,already,submitted,now);
    }
    public IntegrityLockResult unlockIntegrityExam(UUID examId,UUID actor) {
        examLock(examId);var existing=integrityLocks.findById(examId);existing.ifPresent(integrityLocks::delete);
        return new IntegrityLockResult(examId,false,existing.isEmpty(),0,Instant.now());
    }
    /**
     * Shuffles question order but keeps the questions of one reading/listening passage together, in their own order,
     * as one block where the first of them stood.
     */
    static List<ExamClient.Question> shuffleKeepingPassages(List<ExamClient.Question> questions,Random random){
        var blocks=new ArrayList<List<ExamClient.Question>>();var byPassage=new HashMap<String,List<ExamClient.Question>>();
        for(var q:questions){
            String key=q.passageKey();
            if(key==null){blocks.add(new ArrayList<>(List.of(q)));continue;}
            var block=byPassage.get(key);
            if(block==null){block=new ArrayList<>();byPassage.put(key,block);blocks.add(block);}
            block.add(q);
        }
        Collections.shuffle(blocks,random);
        return blocks.stream().flatMap(List::stream).toList();
    }

    /**
     * MULTIPLE_RESPONSE: a JSON array of distinct option ids, e.g. ["id1","id3"].
     * TRUE_FALSE_GROUP: a JSON object option id -> true/false; statements left out are unanswered.
     */
    static void checkStructured(com.fasterxml.jackson.databind.ObjectMapper json,ExamClient.Question q,String content){
        boolean multi="MULTIPLE_RESPONSE".equals(q.questionType()),group="TRUE_FALSE_GROUP".equals(q.questionType());
        if(!multi&&!group)return;
        var ids=new HashSet<String>();q.options().forEach(o->ids.add(o.id().toString()));
        com.fasterxml.jackson.databind.JsonNode node;
        try{node=json.readTree(content);}catch(Exception e){throw error(400,"Answer must be JSON for this question type");}
        if(multi){
            if(node==null||!node.isArray())throw error(400,"Answer must be a list of option ids");
            var seen=new HashSet<String>();
            for(var v:node)if(!v.isTextual()||!ids.contains(v.asText())||!seen.add(v.asText()))throw error(400,"Option outside this question");
        }else{
            if(node==null||!node.isObject())throw error(400,"Answer must map statements to true/false");
            var fields=node.fields();
            while(fields.hasNext()){var f=fields.next();if(!ids.contains(f.getKey())||!f.getValue().isBoolean())throw error(400,"Option outside this question");}
        }
    }

    // Do not roll back AUTO_SUBMITTED when rejecting a late answer.
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public void saveAnswer(UUID sessionId,UUID userId,UUID questionId,String content){
        var s=owned(sessionId,userId);expire(s);
        if(s.getStatus()!=ExamSession.Status.IN_PROGRESS)throw error(409,"Session ended");
        var q=s.getQuestions().stream().filter(v->v.questionId().equals(questionId)).findFirst().orElseThrow(()->error(400,"Question outside this exam"));
        if(content==null || content.length()>20000)throw error(400,"Invalid answer length");
        if(Set.of("MULTIPLE_CHOICE","TRUE_FALSE").contains(q.questionType()) && !content.isEmpty() && q.options().stream().noneMatch(o->o.id().toString().equals(content)))throw error(400,"Option outside this question");
        if("SPEAKING".equals(q.questionType()) && !content.isEmpty())throw error(400,"Speaking answers are recorded through the audio upload");
        if(!content.isEmpty())checkStructured(json,q,content);
        var a=answers.findBySessionIdAndQuestionId(sessionId,questionId).orElseGet(()->SessionAnswer.builder().session(s).questionId(questionId).build());
        a.setAnswerContent(content);answers.save(a);
    }
    public void submitExam(UUID id,UUID user){var s=owned(id,user);expire(s);if(s.getStatus()==ExamSession.Status.IN_PROGRESS)finish(s,ExamSession.Status.SUBMITTED);}
    public void autoSubmitExam(UUID id){expire(load(id));}
    public SessionDetailDto getSessionDetails(UUID id){var s=load(id);expire(s);return new SessionDetailDto(s.getId(),s.getExamId(),s.getUserId(),s.getStatus().name(),s.getExamTitle(),s.getAttemptNumber(),s.getStartTime(),s.getEndTime(),answers.findBySessionId(id).stream().map(a->new SessionDetailDto.AnswerDto(a.getQuestionId(),a.getAnswerContent())).toList(),s.getInvalidatedAt(),s.getInvalidationReason());}
    public boolean resumable(UUID exam,UUID user){return sessions.findActiveSession(exam,user).filter(s->s.getExpiresAt().isAfter(Instant.now())).isPresent();}
    public long attempts(UUID exam,UUID user){return sessions.countByExamIdAndUserId(exam,user);}
    public boolean hasStarted(UUID exam){return sessions.existsByExamId(exam);}
    public ScheduleAvailability availability(UUID exam,UUID user) {
        Instant now=Instant.now();var active=sessions.findActiveSession(exam,user).filter(s->s.getExpiresAt().isAfter(now)).orElse(null);
        var latest=sessions.findTopByExamIdAndUserIdOrderByAttemptNumberDesc(exam,user).orElse(null);
        if(latest!=null && latest.getStatus()==ExamSession.Status.IN_PROGRESS && !now.isBefore(latest.getExpiresAt())) {
            latest=load(latest.getId());
            expire(latest);
        }
        return new ScheduleAvailability(sessions.countByExamIdAndUserId(exam,user),active!=null,active==null?null:active.getExpiresAt(),sessions.countByExamIdAndStatusAndExpiresAtAfter(exam,ExamSession.Status.IN_PROGRESS,now),latest==null?null:latest.getId(),latest==null?null:latest.getStatus().name());
    }
    public List<RecipientProgress> recipientProgress(UUID exam,List<UUID> users) {
        if(users==null || users.size()>100)throw error(400,"At most 100 recipient IDs are allowed");
        var grouped=new HashMap<UUID,List<ExamSession>>();
        Instant now=Instant.now();
        sessions.findByExamIdAndUserIdIn(exam,users).forEach(s->{
            if(s.getStatus()==ExamSession.Status.IN_PROGRESS && !now.isBefore(s.getExpiresAt())) {s=load(s.getId());expire(s);}
            grouped.computeIfAbsent(s.getUserId(),ignored->new ArrayList<>()).add(s);
        });
        return users.stream().distinct().map(user->{
            var rows=grouped.getOrDefault(user,List.of());
            var latest=rows.stream().max(Comparator.comparingInt(ExamSession::getAttemptNumber)).orElse(null);
            boolean active=rows.stream().anyMatch(s->s.getStatus()==ExamSession.Status.IN_PROGRESS);
            boolean submitted=!active && latest!=null && (latest.getStatus()==ExamSession.Status.SUBMITTED||latest.getStatus()==ExamSession.Status.AUTO_SUBMITTED);
            return new RecipientProgress(user,!rows.isEmpty(),submitted);
        }).toList();
    }
    public AdmissionStatus admissionStatus(UUID exam,UUID admission) {
        examLock(exam);
        var session=sessions.findByExamIdAndStartAdmissionId(exam,admission).orElse(null);
        return new AdmissionStatus(exam,admission,session==null?null:session.getId(),session!=null);
    }
    public UUID owner(UUID id){return load(id).getUserId();}
    public List<Monitor> monitor(UUID examId,UUID actor){exams.permission(examId,actor,false,token);return sessions.findByExamId(examId).stream().map(s->{s=load(s.getId());expire(s);return new Monitor(s.getId(),s.getUserId(),s.getStatus().name(),s.getAttemptNumber(),s.getStartTime(),s.getExpiresAt(),s.isInvalidated(),s.getInvalidatedAt(),s.getInvalidationReason());}).toList();}
    // ---- Participant & Attempt management: attempt history, summary and invalidation ----
    public record AttemptDto(UUID sessionId,int attemptNumber,String status,LocalDateTime startedAt,LocalDateTime endedAt,Instant expiresAt,boolean invalidated,Instant invalidatedAt,String invalidationReason){}
    public record StaffAttemptDto(UUID sessionId,UUID userId,int attemptNumber,String status,LocalDateTime startedAt,LocalDateTime endedAt,Instant expiresAt,boolean invalidated,Instant invalidatedAt,UUID invalidatedBy,String invalidationReason){}
    /** Attempts consumed by a Start (invalidated ones included: invalidation never refunds). */
    public record AttemptSummary(UUID userId,long used,long valid,long invalidated,boolean active,UUID latestSessionId,String latestStatus,Integer latestAttemptNumber){}
    private static AttemptDto attempt(SessionRepository.AttemptRow r){return new AttemptDto(r.sessionId(),r.attemptNumber(),r.status().name(),r.startTime(),r.endTime(),r.expiresAt(),r.invalidatedAt()!=null,r.invalidatedAt(),r.invalidationReason());}
    @Transactional(readOnly=true)
    public List<AttemptDto> myAttempts(UUID exam,UUID user){return sessions.attemptRows(exam,List.of(user)).stream().map(SessionService::attempt).toList();}
    @Transactional(readOnly=true)
    public List<StaffAttemptDto> staffAttempts(UUID exam,UUID student,UUID actor){
        exams.permission(exam,actor,false,token);
        return sessions.attemptRows(exam,List.of(student)).stream().map(r->new StaffAttemptDto(r.sessionId(),r.userId(),r.attemptNumber(),r.status().name(),r.startTime(),r.endTime(),r.expiresAt(),r.invalidatedAt()!=null,r.invalidatedAt(),null,r.invalidationReason())).toList();
    }
    /** One batched query for a roster page; no per-participant calls. */
    @Transactional(readOnly=true)
    public List<AttemptSummary> attemptSummary(UUID exam,List<UUID> users){
        if(users==null||users.isEmpty()||users.size()>100)throw error(400,"Between 1 and 100 user IDs are required");
        Instant now=Instant.now();var byUser=new LinkedHashMap<UUID,List<SessionRepository.AttemptRow>>();
        users.stream().distinct().forEach(u->byUser.put(u,new ArrayList<>()));
        sessions.attemptRows(exam,byUser.keySet()).forEach(r->byUser.get(r.userId()).add(r));
        return byUser.entrySet().stream().map(e->{
            var rows=e.getValue();var latest=rows.stream().max(Comparator.comparingInt(SessionRepository.AttemptRow::attemptNumber)).orElse(null);
            long invalid=rows.stream().filter(r->r.invalidatedAt()!=null).count();
            boolean active=rows.stream().anyMatch(r->r.status()==ExamSession.Status.IN_PROGRESS&&r.expiresAt().isAfter(now));
            return new AttemptSummary(e.getKey(),rows.size(),rows.size()-invalid,invalid,active,latest==null?null:latest.sessionId(),latest==null?null:latest.status().name(),latest==null?null:latest.attemptNumber());
        }).toList();
    }
    /**
     * Invalidation keeps the session, answers, result and audit but marks the attempt invalid and stops an active one.
     * It never refunds the consumed attempt; a new attempt needs an explicit grant in exam-service.
     */
    public StaffAttemptDto invalidate(UUID sessionId,UUID actor,String reason){
        String publicReason=reason==null?"":reason.strip();
        if(publicReason.isEmpty())throw error(400,"Public invalidation reason required");
        if(publicReason.length()>1000)throw error(400,"Invalidation reason too long");
        if(token.isBlank())throw error(503,"Exam service integration not configured");
        var probe=sessions.findById(sessionId).orElseThrow(()->error(404,"Session not found"));
        exams.permission(probe.getExamId(),actor,true,token);
        examLock(probe.getExamId());
        var s=load(sessionId);expire(s);
        if(!s.isInvalidated()){
            Instant now=Instant.now();
            if(s.getStatus()==ExamSession.Status.IN_PROGRESS){s.setStatus(ExamSession.Status.CANCELLED);s.setEndTime(LocalDateTime.now(ZoneOffset.UTC));}
            s.setInvalidatedAt(now);s.setInvalidatedBy(actor);s.setInvalidationReason(publicReason);
            sessions.saveAndFlush(s);
            try{
                var payload=new java.util.LinkedHashMap<String,Object>();
                payload.put("sessionId",s.getId());payload.put("examId",s.getExamId());payload.put("userId",s.getUserId());
                payload.put("attemptNumber",s.getAttemptNumber());payload.put("status",s.getStatus().name());
                payload.put("reason",publicReason);payload.put("actor",actor);payload.put("invalidatedAt",now.toString());
                attemptEvents.save(new AttemptEvent(UUID.randomUUID(),"exam.attempt.invalidated",s.getId(),json.writeValueAsString(payload),now));
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Cannot encode attempt event",e);}
        }
        return new StaffAttemptDto(s.getId(),s.getUserId(),s.getAttemptNumber(),s.getStatus().name(),s.getStartTime(),s.getEndTime(),s.getExpiresAt(),true,s.getInvalidatedAt(),s.getInvalidatedBy(),s.getInvalidationReason());
    }
    private StartSessionResponse response(ExamSession s){var saved=new HashMap<UUID,String>();answers.findBySessionId(s.getId()).forEach(a->saved.put(a.getQuestionId(),a.getAnswerContent()));return new StartSessionResponse(s.getId(),s.getExamId(),s.getExamTitle(),s.getStatus().name(),s.getAttemptNumber(),s.getExpiresAt(),Instant.now(),s.getQuestions(),saved);}
    public record StartSessionResponse(UUID sessionId,UUID examId,String title,String status,int attemptNumber,Instant expiresAt,Instant serverNow,List<ExamClient.Question> questions,Map<UUID,String> answers){}
    public record ScheduleAvailability(long attempts,boolean resumable,Instant activeExpiresAt,long activeSessions,UUID latestSessionId,String latestSessionStatus){}
    public record RecipientProgress(UUID userId,boolean started,boolean submitted){}
    public record AdmissionStatus(UUID examId,UUID admissionId,UUID sessionId,boolean started){}
    public record IntegrityLockResult(UUID examId,boolean locked,boolean unchanged,int activeSessionsSubmitted,Instant changedAt){}
    public record Monitor(UUID sessionId,UUID userId,String status,int attemptNumber,LocalDateTime startedAt,Instant expiresAt,boolean invalidated,Instant invalidatedAt,String invalidationReason){}
    public record SessionDetailDto(UUID sessionId,UUID examId,UUID userId,String status,String examTitle,int attemptNumber,LocalDateTime startedAt,LocalDateTime submittedAt,List<AnswerDto> answers,Instant invalidatedAt,String invalidationReason){public record AnswerDto(UUID questionId,String answerContent){}}
}
