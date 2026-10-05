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
        long attempts=sessions.countByExamIdAndUserId(examId,user);
        if(attempts>=exam.attemptLimit())throw error(409,"Attempt limit reached");
        int limit=Math.min(platformLimit,exam.maxConcurrentSessions());
        if(sessions.countByExamIdAndStatus(examId,ExamSession.Status.IN_PROGRESS)>=limit)
            throw error(409,"Kỳ thi hiện đã đạt giới hạn người đang làm bài. Vui lòng thử lại sau.");
        var questions=new ArrayList<>(exams.safe(examId,token));
        if(questions.isEmpty())throw error(409,"Published snapshot unavailable");
        if(exam.shuffleQuestions())Collections.shuffle(questions);
        if(exam.shuffleOptions())for(int i=0;i<questions.size();i++){var q=questions.get(i);var options=new ArrayList<>(q.options());Collections.shuffle(options);questions.set(i,new ExamClient.Question(q.questionId(),q.questionVersion(),q.questionType(),q.content(),options,q.points(),q.position()));}
        Instant now=Instant.now();
        var s=ExamSession.builder().examId(examId).userId(user).startTime(LocalDateTime.ofInstant(now,ZoneOffset.UTC))
            .expiresAt(now.plusSeconds(exam.durationMinutes()*60L)).status(ExamSession.Status.IN_PROGRESS)
            .attemptNumber(Math.toIntExact(attempts+1)).questions(questions).examTitle(exam.title()).build();
        // Immutable snapshot order is persisted for this attempt and reused on refresh.
        sessions.saveAndFlush(s);return response(s);
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
    public SessionDetailDto getSessionDetails(UUID id){var s=load(id);expire(s);return new SessionDetailDto(s.getId(),s.getExamId(),s.getUserId(),s.getStatus().name(),s.getExamTitle(),s.getStartTime(),s.getEndTime(),answers.findBySessionId(id).stream().map(a->new SessionDetailDto.AnswerDto(a.getQuestionId(),a.getAnswerContent())).toList());}
    public boolean resumable(UUID exam,UUID user){return sessions.findActiveSession(exam,user).filter(s->s.getExpiresAt().isAfter(Instant.now())).isPresent();}
    public long attempts(UUID exam,UUID user){return sessions.countByExamIdAndUserId(exam,user);}
    public UUID owner(UUID id){return load(id).getUserId();}
    public List<Monitor> monitor(UUID examId,UUID actor){exams.permission(examId,actor,false,token);return sessions.findByExamId(examId).stream().map(s->{s=load(s.getId());expire(s);return new Monitor(s.getId(),s.getUserId(),s.getStatus().name(),s.getAttemptNumber(),s.getStartTime(),s.getExpiresAt());}).toList();}
    private StartSessionResponse response(ExamSession s){var saved=new HashMap<UUID,String>();answers.findBySessionId(s.getId()).forEach(a->saved.put(a.getQuestionId(),a.getAnswerContent()));return new StartSessionResponse(s.getId(),s.getExamId(),s.getExamTitle(),s.getStatus().name(),s.getAttemptNumber(),s.getExpiresAt(),Instant.now(),s.getQuestions(),saved);}
    public record StartSessionResponse(UUID sessionId,UUID examId,String title,String status,int attemptNumber,Instant expiresAt,Instant serverNow,List<ExamClient.Question> questions,Map<UUID,String> answers){}
    public record Monitor(UUID sessionId,UUID userId,String status,int attemptNumber,LocalDateTime startedAt,Instant expiresAt){}
    public record SessionDetailDto(UUID sessionId,UUID examId,UUID userId,String status,String examTitle,LocalDateTime startedAt,LocalDateTime submittedAt,List<AnswerDto> answers){public record AnswerDto(UUID questionId,String answerContent){}}
}
