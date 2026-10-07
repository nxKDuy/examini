package com.datn.grading.service;

import com.datn.grading.entity.*;
import com.datn.grading.repository.GradingOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class GradingEventPublisher {
    private final GradingOutboxRepository outbox;
    private final ObjectMapper json;

    public void recorded(ExamResult result,int attemptNumber) {
        Instant now=Instant.now();UUID eventId=UUID.randomUUID();
        var questions=result.getQuestionGrades().stream().sorted(Comparator.comparingInt(QuestionGrade::getPosition)).map(q->
                new QuestionState(q.getQuestionId(),q.getQuestionVersion(),q.getQuestionType(),q.getPosition(),decimal(q.getPointValue()),
                        q.getAnswerContent()!=null&&!q.getAnswerContent().isBlank(),q.isNeedsReview(),q.isNeedsReview()?null:decimal(q.getScore()))).toList();
        var event=new RecordedEvent(eventId,1,now,result.getId(),result.getResultRevision(),result.getSessionId(),result.getExamId(),
                result.getUserId(),attemptNumber,result.getStatus().name(),decimal(result.getTotalScore()),decimal(result.getMaxScore()),
                result.getSubmittedAt(),result.getGradedAt(),questions);
        enqueue(eventId,result,"examini.grading.events","grading.result.recorded.v1",event,now);
    }

    public void reviewRequired(ExamResult result) {
        notification(result,"grading.review.required",new ReviewRequired(result.getId(),result.getSessionId(),result.getExamId(),result.getExamTitle(),result.getResultRevision()));
    }

    public void released(ExamResult result,boolean regraded) {
        String type=regraded?"grading.result.regraded":"grading.result.released";
        Object payload=regraded
                ?new Regraded(result.getSessionId(),result.getExamId(),result.getExamTitle(),result.getUserId(),result.getResultRevision())
                :new Released(result.getId(),result.getResultRevision(),result.getSessionId(),result.getExamId(),result.getExamTitle(),result.getUserId(),result.getGradingMode());
        notification(result,type,payload);
    }

    private void notification(ExamResult result,String type,Object payload) {
        Instant now=Instant.now();UUID eventId=UUID.randomUUID();
        enqueue(eventId,result,"examini.events",type,new Envelope(eventId,type,1,now,payload),now);
    }
    private void enqueue(UUID eventId,ExamResult result,String exchange,String route,Object payload,Instant now) {
        try {outbox.save(new GradingOutboxEvent(eventId,result.getId(),result.getResultRevision(),exchange,route,json.writeValueAsString(payload),now));}
        catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode grading event",e);}
    }
    private static BigDecimal decimal(Float value){return value==null?null:BigDecimal.valueOf(value.doubleValue());}

    public record RecordedEvent(UUID eventId,int schemaVersion,Instant occurredAt,UUID resultId,long resultRevision,
                                UUID sessionId,UUID examId,UUID candidateId,Integer attemptNumber,String status,
                                BigDecimal totalScore,BigDecimal maxScore,Instant submittedAt,Instant gradedAt,List<QuestionState> questions){}
    public record QuestionState(UUID questionId,long questionVersion,String questionType,int position,BigDecimal pointValue,
                                boolean answered,boolean needsReview,BigDecimal awardedPoints){}
    public record Envelope(UUID eventId,String eventType,int eventVersion,Instant occurredAt,Object payload){}
    public record ReviewRequired(UUID resultId,UUID sessionId,UUID examId,String examTitle,long resultRevision){}
    public record Released(UUID resultId,long resultRevision,UUID sessionId,UUID examId,String examTitle,UUID studentUserId,String gradingMode){}
    public record Regraded(UUID sessionId,UUID examId,String examTitle,UUID studentUserId,long resultRevision){}
}
