package com.datn.grading.service;
import com.datn.grading.client.*;
import com.datn.grading.dto.SubmissionDto;
import com.datn.grading.entity.*;
import com.datn.grading.repository.*;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.Duration;
@Service @RequiredArgsConstructor @Transactional
public class GradingService {
    private final ExamSessionClient sessions;private final ExamClient exams;private final GeminiClient ai;
    private final ExamResultRepository results;private final EntityManager em;
    @Value("${exam.internal-token:}") private String token="";
    private static ResponseStatusException error(int status,String msg){return new ResponseStatusException(HttpStatus.valueOf(status),msg);}
    @RabbitListener(queues="exam.submitted.queue")
    public void processSubmittedExam(String message){
        UUID id=UUID.fromString(message);
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(cast(:session as text),0))").setParameter("session",id.toString()).getSingleResult();
        if(results.findBySessionId(id).isPresent())return;
        if(token.isBlank())throw new IllegalStateException("Internal integration not configured");
        var session=sessions.get(id,token);
        if(!Set.of("SUBMITTED","AUTO_SUBMITTED").contains(session.status()))throw new IllegalStateException("Session not submitted");
        var keys=exams.key(session.examId(),token);if(keys.isEmpty())throw new IllegalStateException("Snapshot missing");
        var answers=new HashMap<UUID,String>();session.answers().forEach(a->answers.put(a.questionId(),a.answerContent()));
        var result=ExamResult.builder().sessionId(id).examId(session.examId()).userId(session.userId()).status(ExamResult.Status.GRADED).build();
        result.setExamTitle(session.examTitle());result.setSubmittedAt(session.submittedAt());result.setDurationSeconds(Math.toIntExact(Duration.between(session.startedAt(),session.submittedAt()).getSeconds()));
        float score=0,max=0;
        for(var q:keys){
            max+=q.points().floatValue();String answer=answers.get(q.questionId());
            var grade=QuestionGrade.builder().examResult(result).questionId(q.questionId()).build();
            grade.setQuestionContent(q.content());grade.setQuestionType(q.questionType());grade.setPointValue(q.points().floatValue());grade.setAnswerContent(answer);
            if(answer!=null && !answer.isBlank()){
                if("ESSAY".equals(q.questionType()))try{var g=ai.gradeEssay(q.content(),answer);grade.setScore(g.score()*q.points().floatValue());grade.setFeedback(g.feedback());}
                catch(RuntimeException failure){grade.setNeedsReview(true);grade.setFeedback("Manual essay review required");result.setStatus(ExamResult.Status.NEEDS_REVIEW);}
                else grade.setScore(ObjectiveScoring.score(q,answer));
                q.options().stream().filter(o->o.id().toString().equals(answer)).findFirst().ifPresent(o->grade.setSelectedOptionContent(o.content()));
            }
            score+=grade.getScore();result.getQuestionGrades().add(grade);
        }
        result.setMaxScore(max);result.setTotalScore(score);results.saveAndFlush(result);
        // Infrastructure failures escape: broker redelivery, never silently lose grading.
    }
    private ExamResult load(UUID id){return results.findById(id).orElseThrow(()->error(404,"Result not found"));}
    private void manage(UUID exam,UUID actor){exams.permission(exam,actor,true,token);}
    public Page<SubmissionDto.SummaryResponse> getSubmissions(UUID exam,String status,Pageable page,UUID actor){
        if(exam==null)throw error(400,"Choose an exam");manage(exam,actor);
        ExamResult.Status filter=null;if(status!=null && !status.isBlank())try{filter=ExamResult.Status.valueOf(status);}catch(IllegalArgumentException e){throw error(400,"Unknown grading status");}
        return results.searchResults(exam,filter,page).map(this::summary);
    }
    public SubmissionDto.DetailResponse detail(UUID id,UUID actor){var r=load(id);manage(r.getExamId(),actor);
        return new SubmissionDto.DetailResponse(r.getId(),r.getExamId(),r.getExamTitle(),r.getUserId(),r.getUserId().toString(),r.getUserId().toString(),r.getStatus().name(),r.getTotalScore(),r.getMaxScore(),r.getSubmittedAt(),r.getDurationSeconds(),r.getFeedback(),
            r.getQuestionGrades().stream().map(q->new SubmissionDto.AnswerDetailDto(q.getQuestionId(),q.getQuestionContent(),q.getQuestionType(),q.getPointValue(),q.getAnswerContent(),q.getSelectedOptionContent(),q.getAnswerContent(),q.isNeedsReview()?null:q.getScore()>0,q.getScore())).toList());
    }
    public SubmissionDto.SummaryResponse grade(UUID id,SubmissionDto.ManualGradeRequest req,UUID actor){var r=load(id);manage(r.getExamId(),actor);
        if(req.score()==null || !Float.isFinite(req.score()) || req.score()<0 || req.score()>r.getMaxScore())throw error(400,"Score outside snapshot total");
        r.setTotalScore(req.score());r.setFeedback(req.feedback());r.setGradedBy(actor);r.setStatus(ExamResult.Status.GRADED);return summary(r);
    }
    private SubmissionDto.SummaryResponse summary(ExamResult r){return new SubmissionDto.SummaryResponse(r.getId(),r.getExamId(),r.getExamTitle(),r.getUserId(),r.getUserId().toString(),r.getUserId().toString(),r.getStatus().name(),r.getTotalScore(),r.getMaxScore(),r.getSubmittedAt(),r.getDurationSeconds(),r.getFeedback());}
    public StudentResult student(UUID session,UUID actor){
        var r=results.findBySessionId(session);
        if(r.isEmpty()){var s=sessions.get(session,token);if(!s.userId().equals(actor))throw error(403,"Not your result");return new StudentResult(session,s.examTitle(),Set.of("SUBMITTED","AUTO_SUBMITTED").contains(s.status())?"GRADING":s.status(),null,null,null);}
        var result=r.get();if(!result.getUserId().equals(actor))throw error(403,"Not your result");
        return new StudentResult(session,result.getExamTitle(),result.getStatus().name(),result.getStatus()==ExamResult.Status.GRADED?result.getTotalScore():null,result.getMaxScore(),result.getFeedback());
    }
    public record StudentResult(UUID sessionId,String title,String status,Float score,Float totalScore,String feedback){}
}
