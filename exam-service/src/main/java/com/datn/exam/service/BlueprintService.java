package com.datn.exam.service;

import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.dto.BlueprintDto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/**
 * Builds an unsaved exam draft from blueprint rows. Each row is filled only from its own filters
 * (shortfall is reported, never backfilled), and ids never repeat across rows.
 */
@Service @RequiredArgsConstructor
public class BlueprintService {
    private static final Set<String> TYPES=Set.of("MULTIPLE_CHOICE","TRUE_FALSE","SHORT_ANSWER","ESSAY","SPEAKING","MULTIPLE_RESPONSE","TRUE_FALSE_GROUP");
    private static final Set<String> DIFFICULTIES=Set.of("EASY","MEDIUM","HARD");
    private static final int MAX_QUESTIONS=200,MAX_EXCLUDED=1000;
    private final QuestionBankClient bank;
    @Value("${question-bank.internal-token:}") private String token="";

    public Preview preview(PreviewRequest req,UUID actor) {
        if(req==null || req.rows()==null || req.rows().isEmpty() || req.rows().size()>50)throw ExamCore.error(400,"1 to 50 rows required");
        int requested=0;var pinnedAll=new LinkedHashSet<UUID>();
        for(var row:req.rows()) {
            if(row==null || row.count()<1 || row.count()>MAX_QUESTIONS)throw ExamCore.error(400,"Row count must be 1..200");
            if(row.pointsPerQuestion()==null || row.pointsPerQuestion().signum()<=0 || row.pointsPerQuestion().compareTo(BigDecimal.valueOf(1000))>0)
                throw ExamCore.error(400,"pointsPerQuestion must be > 0 and <= 1000");
            if(row.questionType()!=null && !TYPES.contains(row.questionType()))throw ExamCore.error(400,"Unknown questionType");
            if(row.difficulty()!=null && !DIFFICULTIES.contains(row.difficulty()))throw ExamCore.error(400,"Unknown difficulty");
            var pins=row.pinnedQuestionIds()==null?List.<UUID>of():row.pinnedQuestionIds();
            if(pins.size()>row.count())throw ExamCore.error(400,"pinnedQuestionIds exceed row count");
            for(UUID id:pins)if(id==null || !pinnedAll.add(id))throw ExamCore.error(400,"Pinned questions must be unique across rows");
            requested+=row.count();
        }
        if(requested>MAX_QUESTIONS)throw ExamCore.error(400,"Exam requires 1 to 200 questions");
        var excluded=new LinkedHashSet<UUID>(req.excludeQuestionIds()==null?List.of():req.excludeQuestionIds());
        if(excluded.contains(null))throw ExamCore.error(400,"excludeQuestionIds must not contain null");
        if(token.isBlank())throw ExamCore.error(503,"Question Bank integration not configured");

        // Pinned picks are re-validated against the provider's eligible universe for this actor.
        var details=new HashMap<UUID,QuestionBankClient.Question>();
        if(!pinnedAll.isEmpty())for(var q:bank.eligible(new ArrayList<>(pinnedAll),token,actor,true,false))
            if(q!=null && Boolean.TRUE.equals(q.eligible()))details.put(q.id(),q);
        var used=new LinkedHashSet<UUID>(excluded);used.addAll(pinnedAll);

        var rowPicks=new ArrayList<List<UUID>>();var rowPinned=new ArrayList<Set<UUID>>();
        var rowDropped=new ArrayList<List<UUID>>();var rowAvailable=new ArrayList<Long>();
        for(var row:req.rows()) {
            var pins=row.pinnedQuestionIds()==null?List.<UUID>of():row.pinnedQuestionIds();
            var kept=new ArrayList<UUID>();var dropped=new ArrayList<UUID>();
            for(UUID id:pins)(details.containsKey(id)?kept:dropped).add(id);
            int need=row.count()-kept.size();long available=kept.size();var picks=new ArrayList<>(kept);
            if(need>0) {
                if(used.size()>MAX_EXCLUDED)throw ExamCore.error(400,"Too many excluded questions (max 1000 including picks)");
                var r=bank.eligibleRandom(new QuestionBankClient.EligibleRandomRequest(row.bankId(),row.categoryId(),row.questionType(),
                        row.difficulty(),need,new ArrayList<>(used)),token,actor);
                for(UUID id:r==null||r.ids()==null?List.<UUID>of():r.ids())
                    if(id!=null && picks.size()<row.count() && used.add(id))picks.add(id); // defensive: never trust a duplicate
                available+=r==null?0:Math.max(0,r.available());
            }
            rowPicks.add(picks);rowPinned.add(new HashSet<>(kept));rowDropped.add(dropped);rowAvailable.add(available);
        }
        var fresh=rowPicks.stream().flatMap(List::stream).filter(id->!details.containsKey(id)).toList();
        if(!fresh.isEmpty())for(var q:bank.eligible(fresh,token,actor,true,false))if(q!=null && Boolean.TRUE.equals(q.eligible()))details.put(q.id(),q);

        var rows=new ArrayList<RowResult>();var all=new ArrayList<Question>();
        BigDecimal total=BigDecimal.ZERO;int shortfall=0;
        for(int i=0;i<req.rows().size();i++) {
            var row=req.rows().get(i);var questions=new ArrayList<Question>();
            for(UUID id:rowPicks.get(i)) {
                var q=details.get(id);if(q==null)continue; // provider no longer confirms it: counts as shortfall
                questions.add(new Question(id,q.questionVersion(),q.questionType(),q.content(),row.pointsPerQuestion(),i,rowPinned.get(i).contains(id)));
            }
            int missing=row.count()-questions.size();shortfall+=missing;
            total=total.add(row.pointsPerQuestion().multiply(BigDecimal.valueOf(questions.size())));
            rows.add(new RowResult(i,row.bankId(),row.categoryId(),row.questionType(),row.difficulty(),row.count(),questions.size(),
                    rowAvailable.get(i),missing,row.pointsPerQuestion(),rowDropped.get(i),questions));
            all.addAll(questions);
        }
        long seed=req.seed()!=null?req.seed():new Random().nextLong();
        if(req.shuffle())Collections.shuffle(all,new Random(seed));
        return new Preview(rows,all,requested,all.size(),total,shortfall,shortfall==0,req.shuffle(),seed);
    }

    /** Save-time re-check: every newly added question must still be eligible for the saving actor. */
    public void requireSelectable(Collection<UUID> added,UUID actor) {
        if(added.isEmpty())return;
        if(token.isBlank())throw ExamCore.error(503,"Question Bank integration not configured");
        var ok=new HashSet<UUID>();
        for(var q:bank.eligible(new ArrayList<>(added),token,actor,true,false))if(q!=null && Boolean.TRUE.equals(q.eligible()))ok.add(q.id());
        var rejected=added.stream().filter(id->!ok.contains(id)).toList();
        if(!rejected.isEmpty())throw ExamCore.error(409,"QUESTION_NOT_ELIGIBLE: "+rejected);
    }
}
