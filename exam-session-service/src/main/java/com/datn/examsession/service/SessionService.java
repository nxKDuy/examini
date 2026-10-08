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
    @Value("${exam.internal-token:}") private String token="";
    @Value("${exam.capacity.platform-limit:100}") private int platformLimit=100;
    private static ResponseStatusException error(int code,String msg){return new ResponseStatusException(HttpStatus.valueOf(code),msg);}
    // PostgreSQL transaction lock serializes ALL starts for one exam, across processes.
    private void examLock(UUID exam) {em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(cast(:exam as text),0))").setParameter("exam",exam.toString()).getSingleResult();}
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public StartSessionResponse startExam(UUID examId,UUID user) {
        examLock(examId);
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
        if(attempts>=exam.attemptLimit())throw error(409,"Attempt limit reached");
        int limit=Math.min(platformLimit,exam.maxConcurrentSessions());
        if(sessions.countByExamIdAndStatus(examId,ExamSession.Status.IN_PROGRESS)>=limit)
            throw error(409,"Kỳ thi hiện đã đạt giới hạn người đang làm bài. Vui lòng thử lại sau.");
        var questions=new ArrayList<>(exams.safe(examId,token));
        if(questions.isEmpty())throw error(409,"Published snapshot unavailable");
        if(exam.shuffleQuestions())Collections.shuffle(questions);
        if(exam.shuffleOptions())for(int i=0;i<questions.size();i++){var q=questions.get(i);var options=new ArrayList<>(q.options());Collections.shuffle(options);questions.set(i,new ExamClient.Question(q.questionId(),q.questionVersion(),q.questionType(),q.content(),options,q.points(),q.position()));}
        var admission=exams.claimStart(examId,reservation.admissionId(),user,token);
        Instant now=admission.startedAt();
        var s=ExamSession.builder().examId(examId).userId(user).startTime(LocalDateTime.ofInstant(now,ZoneOffset.UTC))
            .expiresAt(now.plusSeconds(exam.durationMinutes()*60L)).status(ExamSession.Status.IN_PROGRESS)
            .attemptNumber(Math.toIntExact(attempts+1)).questions(questions).examTitle(exam.title()).build();
        s.setExpiresAt(admission.deadlineAt());s.setScheduleRevision(admission.scheduleRevision());s.setStartAdmissionId(admission.admissionId());
        // Immutable snapshot order is persisted for this attempt and reused on refresh.
        sessions.saveAndFlush(s);persistedSessionId[0]=s.getId();return response(s);
    }
    public StartSessionResponse get(UUID id,UUID user) {var s=owned(id,user);expire(s);return response(s);}
    private ExamSession owned(UUID id,UUID user) {var s=load(id);if(!s.getUserId().equals(user))throw error(403,"Not your session");return s;}
    private ExamSession load(UUID id){var s=sessions.lockById(id).orElseThrow(()->error(404,"Session not found"));em.refresh(s);return s;}
    private void expire(ExamSession s){if(s.getStatus()==ExamSession.Status.IN_PROGRESS && !Instant.now().isBefore(s.getExpiresAt()))finish(s,ExamSession.Status.AUTO_SUBMITTED);}
    private void finish(ExamSession s,ExamSession.Status status){s.setStatus(status);s.setEndTime(LocalDateTime.now(ZoneOffset.UTC));events.save(new SubmissionEvent(s.getId()));}
    // Do not roll back AUTO_SUBMITTED when rejecting a late answer.
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public void saveAnswer(UUID sessionId,UUID userId,UUID questionId,String content){
        var s=owned(sessionId,userId);expire(s);
        if(s.getStatus()!=ExamSession.Status.IN_PROGRESS)throw error(409,"Session ended");
        var q=s.getQuestions().stream().filter(v->v.questionId().equals(questionId)).findFirst().orElseThrow(()->error(400,"Question outside this exam"));
        if(content==null || content.length()>20000)throw error(400,"Invalid answer length");
        if(Set.of("MULTIPLE_CHOICE","TRUE_FALSE").contains(q.questionType()) && !content.isEmpty() && q.options().stream().noneMatch(o->o.id().toString().equals(content)))throw error(400,"Option outside this question");
        var a=answers.findBySessionIdAndQuestionId(sessionId,questionId).orElseGet(()->SessionAnswer.builder().session(s).questionId(questionId).build());
        a.setAnswerContent(content);answers.save(a);
    }
    public void submitExam(UUID id,UUID user){var s=owned(id,user);expire(s);if(s.getStatus()==ExamSession.Status.IN_PROGRESS)finish(s,ExamSession.Status.SUBMITTED);}
    public void autoSubmitExam(UUID id){expire(load(id));}
    public SessionDetailDto getSessionDetails(UUID id){var s=load(id);expire(s);return new SessionDetailDto(s.getId(),s.getExamId(),s.getUserId(),s.getStatus().name(),s.getExamTitle(),s.getAttemptNumber(),s.getStartTime(),s.getEndTime(),answers.findBySessionId(id).stream().map(a->new SessionDetailDto.AnswerDto(a.getQuestionId(),a.getAnswerContent())).toList());}
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
    public List<Monitor> monitor(UUID examId,UUID actor){exams.permission(examId,actor,false,token);return sessions.findByExamId(examId).stream().map(s->{s=load(s.getId());expire(s);return new Monitor(s.getId(),s.getUserId(),s.getStatus().name(),s.getAttemptNumber(),s.getStartTime(),s.getExpiresAt());}).toList();}
    private StartSessionResponse response(ExamSession s){var saved=new HashMap<UUID,String>();answers.findBySessionId(s.getId()).forEach(a->saved.put(a.getQuestionId(),a.getAnswerContent()));return new StartSessionResponse(s.getId(),s.getExamId(),s.getExamTitle(),s.getStatus().name(),s.getAttemptNumber(),s.getExpiresAt(),Instant.now(),s.getQuestions(),saved);}
    public record StartSessionResponse(UUID sessionId,UUID examId,String title,String status,int attemptNumber,Instant expiresAt,Instant serverNow,List<ExamClient.Question> questions,Map<UUID,String> answers){}
    public record ScheduleAvailability(long attempts,boolean resumable,Instant activeExpiresAt,long activeSessions,UUID latestSessionId,String latestSessionStatus){}
    public record RecipientProgress(UUID userId,boolean started,boolean submitted){}
    public record AdmissionStatus(UUID examId,UUID admissionId,UUID sessionId,boolean started){}
    public record Monitor(UUID sessionId,UUID userId,String status,int attemptNumber,LocalDateTime startedAt,Instant expiresAt){}
    public record SessionDetailDto(UUID sessionId,UUID examId,UUID userId,String status,String examTitle,int attemptNumber,LocalDateTime startedAt,LocalDateTime submittedAt,List<AnswerDto> answers){public record AnswerDto(UUID questionId,String answerContent){}}
}
