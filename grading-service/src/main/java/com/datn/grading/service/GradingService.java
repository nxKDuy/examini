package com.datn.grading.service;

import com.datn.grading.client.*;
import com.datn.grading.dto.SubmissionDto;
import com.datn.grading.entity.*;
import com.datn.grading.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Transactional
public class GradingService {
    private final ExamSessionClient sessions;private final ExamClient exams;private final AiClient ai;
    private final ExamResultRepository results;private final ResultPublicationRepository publications;
    private final GradingAuditRepository audits;private final GradingEventPublisher events;private final com.datn.grading.repository.GradingOutboxRepository outbox;
    private final EntityManager em;private final ObjectMapper json;
    @Value("${exam.internal-token:}") private String token="";
    private static ResponseStatusException error(int status,String msg){return new ResponseStatusException(HttpStatus.valueOf(status),msg);}

    @RabbitListener(queues="exam.submitted.queue")
    public void processSubmittedExam(String message){
        UUID sessionId=UUID.fromString(message);
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(cast(:session as text),0))").setParameter("session",sessionId.toString()).getSingleResult();
        if(results.findBySessionId(sessionId).isPresent())return;
        configured();
        var session=sessions.get(sessionId,token);
        if(!Set.of("SUBMITTED","AUTO_SUBMITTED").contains(session.status()))throw new IllegalStateException("Session not submitted");
        var keys=exams.key(session.examId(),token);if(keys.isEmpty())throw new IllegalStateException("Snapshot missing");
        var policy=exams.resultPolicy(session.examId(),token).policy();
        String studentDisplayName=session.userId().toString();
        try{var identity=exams.identity(session.examId(),session.userId(),token);if(identity!=null&&identity.displayName()!=null&&!identity.displayName().isBlank())studentDisplayName=identity.displayName();}catch(RuntimeException unavailable){/* identity is optional; stable userId fallback keeps grading available */}
        var answers=session.answers().stream().collect(Collectors.toMap(ExamSessionClient.AnswerDto::questionId,ExamSessionClient.AnswerDto::answerContent,(a,b)->b));
        Instant now=Instant.now();
        var result=ExamResult.builder().sessionId(sessionId).examId(session.examId()).userId(session.userId()).status(ExamResult.Status.GRADED)
                .examTitle(session.examTitle()).studentDisplayName(studentDisplayName).submittedAt(session.submittedAt().toInstant(ZoneOffset.UTC))
                .durationSeconds(Math.toIntExact(Duration.between(session.startedAt(),session.submittedAt()).getSeconds()))
                .attemptNumber(session.attemptNumber()).resultRevision(1).gradingMode("AUTO").invalidatedAt(session.invalidatedAt()).invalidationReason(session.invalidationReason()).build();
        float max=0;
        for(var q:keys){
            max+=q.points().floatValue();String answer=answers.get(q.questionId());
            var grade=QuestionGrade.builder().examResult(result).questionId(q.questionId()).questionVersion(q.questionVersion()).position(q.position())
                    .questionContent(q.content()).questionType(q.questionType()).pointValue(q.points().floatValue()).answerContent(answer).explanation(q.explanation()).presentation(q.presentation()).build();
            if("ESSAY".equals(q.questionType())&&answer!=null&&!answer.isBlank())try{
                var aiGrade=ai.gradeEssay(AnswerTutor.withPassage(q.presentation(),q.content()),q.expectedAnswer(),answer,q.points().floatValue());float score=aiGrade.score()*q.points().floatValue();
                validateScore(score,q.points().floatValue());grade.setAutoScore(score);grade.setFeedback(aiGrade.feedback());
            }catch(RuntimeException unavailable){grade.setAutoScore(null);
            }finally{grade.setScore(null);grade.setNeedsReview(true);result.setStatus(ExamResult.Status.NEEDS_REVIEW);}
            // Voice answers are always graded by a teacher (no automatic or AI score).
            else if("SPEAKING".equals(q.questionType())&&answer!=null&&!answer.isBlank()){grade.setAutoScore(null);grade.setScore(null);grade.setNeedsReview(true);result.setStatus(ExamResult.Status.NEEDS_REVIEW);}
            else {float score=ObjectiveScoring.score(q,answer);grade.setAutoScore(score);grade.setScore(score);}
            if(answer!=null&&q.options()!=null&&!q.options().isEmpty())grade.setSelectedOptionContent(ObjectiveScoring.display(q,answer));
            result.getQuestionGrades().add(grade);
        }
        result.setMaxScore(max);result.setTotalScore(derived(result));result.setPendingManualItemCount(pending(result));
        if(result.getStatus()==ExamResult.Status.GRADED)result.setGradedAt(now);
        results.saveAndFlush(result);audit(result,null,"RESULT_CREATED",null,null,now);
        if(result.getStatus()==ExamResult.Status.GRADED&&policy.autoRelease()&&!result.isInvalidated())releaseCurrent(result,false,now);
        events.recorded(result,session.attemptNumber());
        if(result.getStatus()==ExamResult.Status.NEEDS_REVIEW&&!result.isInvalidated())events.reviewRequired(result);
    }

    /** Attempt invalidation is owned by exam-session-service; grading only mirrors it and never reverses it. */
    @RabbitListener(queues="exam.attempt.invalidated.queue")
    public void processAttemptInvalidated(String message){
        try{
            var node=json.readTree(message);
            UUID sessionId=UUID.fromString(node.path("sessionId").asText());
            Instant at=Instant.parse(node.path("invalidatedAt").asText());
            String reason=node.path("reason").asText(null);UUID actor=node.hasNonNull("actor")?UUID.fromString(node.get("actor").asText()):null;
            em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(cast(:session as text),0))").setParameter("session",sessionId.toString()).getSingleResult();
            var found=results.findBySessionId(sessionId);
            if(found.isEmpty()||found.get().isInvalidated())return;
            var result=results.lockById(found.get().getId()).orElseThrow();
            result.setInvalidatedAt(at);result.setInvalidationReason(reason);
            audit(result,actor,"RESULT_INVALIDATED",reason,null,at);
        }catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException|java.time.format.DateTimeParseException malformed){
            throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("Malformed attempt invalidation event",malformed);
        }
    }

    public Page<SubmissionDto.SummaryResponse> getSubmissions(UUID exam,String status,Boolean released,Pageable page,UUID actor){
        if(exam==null)throw error(400,"Choose an exam");manage(exam,actor);
        ExamResult.Status filter=null;if(status!=null&&!status.isBlank())try{filter=ExamResult.Status.valueOf(status);}catch(IllegalArgumentException e){throw error(400,"Unknown grading status");}
        return results.searchResults(exam,filter,released,page).map(this::summary);
    }
    public SubmissionDto.Overview overview(UUID exam,UUID actor){manage(exam,actor);return new SubmissionDto.Overview(results.countByExamIdAndInvalidatedAtIsNull(exam),results.countByExamIdAndStatusAndInvalidatedAtIsNull(exam,ExamResult.Status.NEEDS_REVIEW),results.countByExamIdAndStatusAndInvalidatedAtIsNull(exam,ExamResult.Status.PENDING),results.countByExamIdAndStatusAndInvalidatedAtIsNull(exam,ExamResult.Status.GRADED),results.countByExamIdAndReleasedRevisionIsNotNullAndInvalidatedAtIsNull(exam),results.sumPendingManualItems(exam),results.countByExamIdAndInvalidatedAtIsNotNull(exam));}
    public SubmissionDto.DetailResponse detail(UUID id,UUID actor){var r=load(id,false);manage(r.getExamId(),actor);return detail(r);}

    /** On-demand AI score suggestion for one essay item (nothing is saved: the teacher applies it in the draft). */
    public record AiSuggestion(float score,String feedback,String model){}
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public AiSuggestion suggestEssay(UUID id,UUID questionId,UUID actor){
        var r=load(id,false);manage(r.getExamId(),actor);
        var item=r.getQuestionGrades().stream().filter(q->q.getQuestionId().equals(questionId)).findFirst().orElseThrow(()->error(404,"Question not in this submission"));
        if(!"ESSAY".equals(item.getQuestionType()))throw error(400,"AI suggestions are for essay questions");
        if(item.getAnswerContent()==null||item.getAnswerContent().isBlank())throw error(409,"Thí sinh không trả lời câu này");
        if(!ai.available())throw error(503,"Chưa cấu hình AI");
        String guide=exams.key(r.getExamId(),configured()).stream().filter(q->q.questionId().equals(questionId)).map(ExamClient.Question::expectedAnswer).findFirst().orElse(null);
        try{
            var g=ai.gradeEssay(AnswerTutor.withPassage(item.getPresentation(),item.getQuestionContent()),guide,item.getAnswerContent(),item.getPointValue());
            float score=Math.round(g.score()*item.getPointValue()*4)/4f;
            return new AiSuggestion(Math.min(item.getPointValue(),Math.max(0,score)),g.feedback(),g.model());
        }catch(IllegalStateException e){throw error(503,"AI tạm thời không phản hồi, hãy thử lại sau");}
    }

    public SubmissionDto.DetailResponse saveDraft(UUID id,SubmissionDto.SaveDraftRequest request,UUID actor){
        var result=load(id,true);manage(result.getExamId(),actor);expected(result,request.expectedRevision());
        notInvalidated(result);
        if(result.getStatus()==ExamResult.Status.GRADED)throw error(409,"Start an explicit regrade before changing a finalized result");
        if(request.items()==null)throw error(400,"items required");
        var byId=result.getQuestionGrades().stream().collect(Collectors.toMap(QuestionGrade::getQuestionId,Function.identity()));
        var seen=new HashSet<UUID>();var changes=new ArrayList<AuditChange>();Instant now=Instant.now();String oldOverall=result.getFeedback();
        for(var change:request.items()){
            if(change==null||change.questionId()==null||!seen.add(change.questionId()))throw error(400,"Missing or duplicate questionId");
            var item=Optional.ofNullable(byId.get(change.questionId())).orElseThrow(()->error(400,"Question outside frozen snapshot"));
            Float oldScore=item.getScore();String oldFeedback=item.getFeedback();String newFeedback=text(change.feedback(),10000,"Item feedback");
            boolean override=change.score()!=null&&ObjectiveScoring.isObjective(item.getQuestionType())&&item.getAutoScore()!=null&&!Objects.equals(change.score(),item.getAutoScore());String overrideReason=override?required(change.overrideReason(),1000,"Objective score override reason"):text(change.overrideReason(),1000,"Override reason");
            if(change.score()!=null){validateScore(change.score(),item.getPointValue());item.setManualScore(change.score());item.setScore(change.score());item.setNeedsReview(false);item.setManualGradedBy(actor);item.setManualGradedAt(now);}
            item.setFeedback(newFeedback);
            if(!Objects.equals(oldScore,item.getScore())||!Objects.equals(oldFeedback,newFeedback))changes.add(new AuditChange(item.getQuestionId(),oldScore,item.getScore(),oldFeedback,newFeedback,overrideReason,override?"OBJECTIVE_SCORE_OVERRIDDEN":"ITEM_UPDATED"));
        }
        result.setFeedback(text(request.feedback(),10000,"Feedback"));result.setGradingMode("MANUAL");result.setGradedAt(null);
        result.setStatus(result.getQuestionGrades().stream().anyMatch(q->q.isNeedsReview()||q.getScore()==null)?ExamResult.Status.NEEDS_REVIEW:ExamResult.Status.PENDING);
        result.setPendingManualItemCount(pending(result));result.setTotalScore(derived(result));next(result);
        changes.forEach(c->audits.save(new GradingAudit(result.getId(),actor,c.action(),result.getResultRevision(),c.questionId(),c.oldScore(),c.newScore(),c.oldFeedback(),c.newFeedback(),c.reason(),now)));
        if(!Objects.equals(oldOverall,result.getFeedback()))audits.save(new GradingAudit(result.getId(),actor,"OVERALL_FEEDBACK_UPDATED",result.getResultRevision(),null,null,null,oldOverall,result.getFeedback(),null,now));
        audit(result,actor,"DRAFT_SAVED",null,null,now);events.recorded(result,attempt(result));return detail(result);
    }

    public SubmissionDto.DetailResponse finalizeResult(UUID id,long expectedRevision,UUID actor){
        var result=load(id,true);manage(result.getExamId(),actor);expected(result,expectedRevision);
        notInvalidated(result);
        if(result.getStatus()==ExamResult.Status.GRADED)throw error(409,"Result already finalized");
        for(var item:result.getQuestionGrades()){
            if(item.isNeedsReview()||item.getScore()==null)throw error(400,"All required items must be resolved");
            validateScore(item.getScore(),item.getPointValue());
        }
        Instant now=Instant.now();result.setTotalScore(derived(result));result.setPendingManualItemCount(0);result.setStatus(ExamResult.Status.GRADED);result.setGradedAt(now);result.setGradedBy(actor);result.setGradingMode("MANUAL");next(result);
        audit(result,actor,"RESULT_FINALIZED",result.getPendingPublicReason(),result.getPendingPrivateAuditNote(),now);
        if(policy(result).policy().autoRelease())releaseCurrent(result,result.getReleasedRevision()!=null,now);
        events.recorded(result,attempt(result));return detail(result);
    }

    public SubmissionDto.DetailResponse release(UUID id,long expectedRevision,UUID actor){
        var result=load(id,true);manage(result.getExamId(),actor);expected(result,expectedRevision);
        notInvalidated(result);
        if(result.getStatus()!=ExamResult.Status.GRADED)throw error(409,"Finalize the result before release");
        if(Objects.equals(result.getReleasedRevision(),result.getResultRevision()))return detail(result);
        boolean regraded=result.getReleasedRevision()!=null;next(result);Instant now=Instant.now();audit(result,actor,"RESULT_RELEASED",result.getPendingPublicReason(),result.getPendingPrivateAuditNote(),now);
        releaseCurrent(result,regraded,now);events.recorded(result,attempt(result));return detail(result);
    }

    public SubmissionDto.DetailResponse beginRegrade(UUID id,SubmissionDto.RegradeRequest request,UUID actor){
        var result=load(id,true);manage(result.getExamId(),actor);expected(result,request.expectedRevision());
        notInvalidated(result);
        if(result.getStatus()!=ExamResult.Status.GRADED)throw error(409,"Only finalized results can be regraded");
        String reason=required(request.publicReason(),1000,"Public regrade reason");String privateNote=text(request.privateAuditNote(),5000,"Private audit note");
        result.setPendingPublicReason(reason);result.setPendingPrivateAuditNote(privateNote);result.setStatus(ExamResult.Status.PENDING);result.setGradedAt(null);result.setGradingMode("MANUAL");next(result);
        audit(result,actor,"REGRADE_STARTED",reason,privateNote,Instant.now());events.recorded(result,attempt(result));return detail(result);
    }

    public SubmissionDto.StudentResult student(UUID sessionId,UUID actor){
        var optional=results.findBySessionId(sessionId);
        if(optional.isEmpty()){var session=sessions.get(sessionId,configured());if(!session.userId().equals(actor))throw error(403,"Not your result");String status=Set.of("SUBMITTED","AUTO_SUBMITTED").contains(session.status())?"SUBMITTED":session.status();return new SubmissionDto.StudentResult(sessionId,session.examId(),session.examTitle(),session.attemptNumber(),status,null,toInstant(session.submittedAt()),null,null,null,null,null,List.of(),List.of(),session.invalidatedAt()!=null,session.invalidatedAt(),session.invalidationReason());}
        var result=optional.get();if(!result.getUserId().equals(actor))throw error(403,"Not your result");
        var latest=publications.findTopByResultIdOrderByResultRevisionDesc(result.getId());
        if(latest.isEmpty()){String status=switch(result.getStatus()){case NEEDS_REVIEW->"NEEDS_REVIEW";case PENDING->"GRADING";case GRADED->"GRADED_UNRELEASED";};return new SubmissionDto.StudentResult(sessionId,result.getExamId(),result.getExamTitle(),result.getAttemptNumber(),status,null,result.getSubmittedAt(),result.getGradedAt(),null,null,null,null,List.of(),List.of(),result.isInvalidated(),result.getInvalidatedAt(),result.getInvalidationReason());}
        var policy=policy(result);var p=policy.policy();var publication=latest.get();var publishedItems=readItems(publication).stream().collect(Collectors.toMap(PublicationItem::questionId,Function.identity()));
        Map<UUID,ExamClient.Question> keys=Map.of();boolean closed="CLOSED".equals(policy.examStatus());boolean reveal=p.showCorrectAnswers()&&closed;boolean revealExplanation=p.showExplanation()&&closed;
        if(reveal)keys=exams.key(result.getExamId(),token).stream().collect(Collectors.toMap(ExamClient.Question::questionId,Function.identity()));
        var finalKeys=keys;
        var items=result.getQuestionGrades().stream().sorted(Comparator.comparingInt(QuestionGrade::getPosition)).map(q->{
            var published=publishedItems.get(q.getQuestionId());
            return new SubmissionDto.StudentItem(q.getQuestionId(),q.getQuestionVersion(),q.getPosition(),q.getQuestionContent(),q.getQuestionType(),q.getPointValue(),q.getAnswerContent(),
                    published.answerDisplay()!=null?published.answerDisplay():answerDisplay(q),p.showQuestionBreakdown()?published.score():null,p.showFeedback()?published.feedback():null,reveal?correct(finalKeys.get(q.getQuestionId())):null,revealExplanation?published.explanation():null,q.getPresentation());
        }).toList();
        var history=publications.findByResultIdOrderByResultRevisionAsc(result.getId()).stream().map(h->new SubmissionDto.PublishedRevision(h.getResultRevision(),h.getReleasedAt(),p.showScore()?h.getTotalScore():null,p.showScore()?result.getMaxScore():null,h.getPublicReason())).toList();
        return new SubmissionDto.StudentResult(sessionId,result.getExamId(),result.getExamTitle(),result.getAttemptNumber(),"GRADED",publication.getResultRevision(),result.getSubmittedAt(),publication.getGradedAt(),publication.getReleasedAt(),
                p.showScore()?publication.getTotalScore():null,p.showScore()?result.getMaxScore():null,p.showFeedback()?publication.getFeedback():null,items,history,result.isInvalidated(),result.getInvalidatedAt(),result.getInvalidationReason());
    }

    private ExamResult load(UUID id,boolean lock){return (lock?results.lockById(id):results.findById(id)).orElseThrow(()->error(404,"Result not found"));}
    private void manage(UUID exam,UUID actor){configured();exams.permission(exam,actor,true,token);}
    private String configured(){if(token.isBlank())throw error(503,"Exam service integration not configured");return token;}
    private ExamClient.ResultPolicy policy(ExamResult result){return exams.resultPolicy(result.getExamId(),configured());}
    private int attempt(ExamResult result){return result.getAttemptNumber();}
    private void notInvalidated(ExamResult result){if(result.isInvalidated())throw error(409,"RESULT_INVALIDATED");}
    private void expected(ExamResult result,long expected){if(expected!=result.getResultRevision())throw error(409,"STALE_RESULT_REVISION");}
    private void next(ExamResult result){result.setResultRevision(Math.addExact(result.getResultRevision(),1));}
    private static float derived(ExamResult r){double total=r.getQuestionGrades().stream().filter(q->q.getScore()!=null).mapToDouble(QuestionGrade::getScore).sum();if(!Double.isFinite(total)||total>Float.MAX_VALUE)throw error(400,"Invalid derived score");return (float)total;}
    private static int pending(ExamResult r){return Math.toIntExact(r.getQuestionGrades().stream().filter(q->q.isNeedsReview()||q.getScore()==null).count());}
    private static Instant toInstant(LocalDateTime value){return value==null?null:value.toInstant(ZoneOffset.UTC);}
    private static void validateScore(Float score,Float max){if(score==null||max==null||!Float.isFinite(score)||!Float.isFinite(max)||score<0||score>max)throw error(400,"Item score outside frozen point value");}
    private static String required(String value,int max,String name){String v=text(value,max,name);if(v==null||v.isBlank())throw error(400,name+" required");return v;}
    private static String text(String value,int max,String name){if(value==null)return null;String v=value.strip();if(v.length()>max)throw error(400,name+" too long");return v;}
    private void audit(ExamResult result,UUID actor,String action,String publicReason,String privateNote,Instant now){audits.save(new GradingAudit(result.getId(),actor,action,result.getResultRevision(),publicReason,privateNote,now));}
    private void releaseCurrent(ExamResult result,boolean regraded,Instant now){
        if(result.getTotalScore()==null)throw error(409,"Final score unavailable");
        try{
            var items=result.getQuestionGrades().stream().map(q->new PublicationItem(q.getQuestionId(),q.getScore(),q.getFeedback(),answerDisplay(q),q.getExplanation())).toList();
            var publication=publications.save(new ResultPublication(result.getId(),result.getResultRevision(),result.getTotalScore(),result.getFeedback(),result.getPendingPublicReason(),result.getGradingMode(),now,result.getGradedAt(),json.writeValueAsString(items)));
            em.flush();em.refresh(publication); // persisted precision, so a backfill rebuilds the identical payload
            events.published(result,publication,awarded(items),regraded);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Cannot freeze result publication",e);}
        result.setReleasedRevision(result.getResultRevision());result.setReleasedScore(result.getTotalScore());result.setPendingPublicReason(null);result.setPendingPrivateAuditNote(null);events.released(result,regraded);
    }
    private static Map<UUID,Float> awarded(List<PublicationItem> items){var m=new HashMap<UUID,Float>();items.forEach(i->m.put(i.questionId(),i.score()));return m;}
    /** Enqueues grading.result.published for publications that predate the event; safe to run repeatedly. */
    @Transactional
    public int backfillPublishedEvents(){
        int added=0;
        for(var publication:publications.findAll(org.springframework.data.domain.Sort.by("resultId","resultRevision"))) {
            if(outbox.existsById(GradingEventPublisher.publishedEventId(publication.getId())))continue;
            var result=results.findById(publication.getResultId()).orElseThrow();
            var first=publications.findByResultIdOrderByResultRevisionAsc(result.getId()).getFirst();
            events.published(result,publication,awarded(readItems(publication)),!first.getId().equals(publication.getId()));added++;
        }
        return added;
    }
    private List<PublicationItem> readItems(ResultPublication publication){try{return json.readValue(publication.getItems(),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Invalid published result snapshot",e);}}
    private static List<String> correct(ExamClient.Question q){return ObjectiveScoring.key(q);}
    private static String answerDisplay(QuestionGrade q){return Set.of("MULTIPLE_CHOICE","TRUE_FALSE","MULTIPLE_RESPONSE","TRUE_FALSE_GROUP","FILL_BLANK","MATCHING").contains(q.getQuestionType())?q.getSelectedOptionContent():q.getAnswerContent();}
    private SubmissionDto.SummaryResponse summary(ExamResult r){return new SubmissionDto.SummaryResponse(r.getId(),r.getExamId(),r.getExamTitle(),r.getUserId(),r.getStudentDisplayName(),r.getAttemptNumber(),r.getStatus().name(),r.getTotalScore(),r.getMaxScore(),r.getReleasedScore(),r.getPendingManualItemCount(),r.getSubmittedAt(),r.getGradedAt(),r.getDurationSeconds(),r.getFeedback(),r.getResultRevision(),r.getReleasedRevision(),r.isInvalidated(),r.getInvalidatedAt(),r.getInvalidationReason());}
    private SubmissionDto.DetailResponse detail(ExamResult r){return new SubmissionDto.DetailResponse(r.getId(),r.getExamId(),r.getExamTitle(),r.getUserId(),r.getStudentDisplayName(),r.getAttemptNumber(),r.getStatus().name(),r.getTotalScore(),r.getMaxScore(),r.getReleasedScore(),r.getPendingManualItemCount(),r.getSubmittedAt(),r.getGradedAt(),r.getDurationSeconds(),r.getFeedback(),r.getResultRevision(),r.getReleasedRevision(),r.getQuestionGrades().stream().sorted(Comparator.comparingInt(QuestionGrade::getPosition)).map(q->new SubmissionDto.ItemResponse(q.getQuestionId(),q.getQuestionVersion(),q.getPosition(),q.getQuestionContent(),q.getQuestionType(),q.getPointValue(),q.getAnswerContent(),q.getSelectedOptionContent(),q.getAutoScore(),q.getManualScore(),q.getScore(),q.isNeedsReview(),q.getFeedback(),q.getManualGradedBy(),q.getManualGradedAt(),q.getPresentation())).toList(),r.isInvalidated(),r.getInvalidatedAt(),r.getInvalidationReason(),r.getSessionId());}
    private record AuditChange(UUID questionId,Float oldScore,Float newScore,String oldFeedback,String newFeedback,String reason,String action){}
    public record PublicationItem(UUID questionId,Float score,String feedback,String answerDisplay,String explanation){}
}
