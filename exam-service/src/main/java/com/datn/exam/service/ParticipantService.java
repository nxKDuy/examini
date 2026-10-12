package com.datn.exam.service;

import com.datn.exam.client.IdentityClient;
import com.datn.exam.client.SessionClient;
import com.datn.exam.dto.ParticipantDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.ExamParticipant;
import com.datn.exam.repository.AttemptGrantRepository;
import com.datn.exam.repository.ExamParticipantRepository;
import com.datn.exam.repository.ExamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;

/** Roster, assignment by ID/email, revoke, grants and the Student's own participation view. */
@Service @RequiredArgsConstructor @Transactional
public class ParticipantService {
    private final ExamCore core;
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final AttemptGrantRepository grants;
    private final SessionClient sessions;
    private final IdentityClient identities;
    @Value("${identity.token:}") private String identityToken="";
    @Value("${exam.internal-token:}") private String internalToken="";

    public ParticipantDto.AddResponse add(UUID id,UUID actor,ParticipantDto.AddRequest request) {
        Exam probe=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));
        core.require(probe,actor,true);
        if(request==null)throw ExamCore.error(400,"Participants required");
        boolean byId=request.userIds()!=null&&!request.userIds().isEmpty(),byEmail=request.emails()!=null&&!request.emails().isEmpty();
        if(byId==byEmail)throw ExamCore.error(400,"Provide exactly one of userIds or emails");
        List<String> inputs=byId?request.userIds().stream().map(v->v==null?null:v.toString()).toList():request.emails().stream().map(v->v==null?null:v.strip().toLowerCase(Locale.ROOT)).toList();
        if(inputs.size()>100||inputs.stream().anyMatch(v->v==null||v.isBlank()))throw ExamCore.error(400,"1 to 100 non-empty values are required");
        var distinct=new LinkedHashSet<>(inputs);
        if(probe.getStatus()!=Exam.Status.DRAFT&&probe.getStatus()!=Exam.Status.PUBLISHED&&probe.getStatus()!=Exam.Status.OPEN)
            throw ExamCore.error(409,"PARTICIPANTS_LOCKED_FOR_CLOSED_EXAM");
        // Resolve identities before taking the Exam lock so a slow provider never holds it.
        var found=resolve(byId?new IdentityClient.Selector(distinct.stream().map(UUID::fromString).toList(),null):new IdentityClient.Selector(null,List.copyOf(distinct)),true);
        Exam e=core.load(id);core.require(e,actor,true);
        Map<String,IdentityClient.Identity> byKey=new HashMap<>();
        for(var identity:found)byKey.put(byId?String.valueOf(identity.userId()):String.valueOf(identity.email()).toLowerCase(Locale.ROOT),identity);
        var results=new ArrayList<ParticipantDto.AddResult>();
        for(String input:distinct) {
            var identity=byKey.get(input);
            if(identity==null){results.add(new ParticipantDto.AddResult(input,byId?UUID.fromString(input):null,null,"NOT_FOUND"));continue;}
            if(!"STUDENT".equals(identity.persona())){results.add(new ParticipantDto.AddResult(input,identity.userId(),identity.displayName(),"NOT_A_STUDENT"));continue;}
            results.add(new ParticipantDto.AddResult(input,identity.userId(),identity.displayName(),core.addParticipant(e,actor,identity.userId()).name()));
        }
        return new ParticipantDto.AddResponse(results,counts(id));
    }

    public ParticipantDto.Row revoke(UUID id,UUID user,UUID actor,String reason) {
        Exam e=core.load(id);core.require(e,actor,true);
        core.revoke(e,actor,user,reason);
        return rows(e,List.of(participants.findByExamIdAndUserId(id,user).orElseThrow())).get(0);
    }

    public ParticipantDto.GrantResponse grant(UUID id,UUID user,UUID actor,ParticipantDto.GrantRequest request) {
        Exam e=core.load(id);core.require(e,actor,true);
        if(request==null)throw ExamCore.error(400,"Grant payload required");
        var result=core.grantAttempts(e,actor,user,request.quantity(),request.reason(),request.requestId());
        var g=result.grant();var p=result.participant();
        return new ParticipantDto.GrantResponse(user,p.getExtraAttempts(),e.getAttemptLimit()+p.getExtraAttempts(),new ParticipantDto.GrantView(g.getId(),g.getQuantity(),g.getReason(),g.getCreatedAt()),result.replayed());
    }

    @Transactional(readOnly=true)
    public List<ParticipantDto.GrantHistory> grantHistory(UUID id,UUID user,UUID actor) {
        Exam e=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));core.require(e,actor,true);
        return grants.findByExamIdAndUserIdOrderByCreatedAtAsc(id,user).stream().map(g->new ParticipantDto.GrantHistory(g.getId(),g.getQuantity(),g.getReason(),g.getActorUserId(),g.getCreatedAt())).toList();
    }

    @Transactional(readOnly=true)
    public ParticipantDto.Roster roster(UUID id,UUID actor,String status,String source,UUID userId,int page,int size) {
        Exam e=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));
        core.require(e,actor,false);
        if(page<0||size<1||size>100)throw ExamCore.error(400,"page must be >=0 and size 1..100");
        ExamParticipant.Status filter=null;
        if(status!=null&&!status.isBlank())try{filter=ExamParticipant.Status.valueOf(status);}catch(IllegalArgumentException ex){throw ExamCore.error(400,"Unknown participant status");}
        String src=source==null||source.isBlank()?null:source;
        if(src!=null&&!Set.of("ASSIGNED","JOIN_CODE","PUBLIC").contains(src))throw ExamCore.error(400,"Unknown participant source");
        Page<ExamParticipant> result=participants.roster(id,filter,src,userId,PageRequest.of(page,size,Sort.by("createdAt","userId")));
        return new ParticipantDto.Roster(rows(e,result.getContent()),page,size,result.getTotalElements(),result.getTotalPages(),counts(id));
    }

    @Transactional(readOnly=true)
    public ParticipantDto.Counts counts(UUID id,UUID actor) {
        Exam e=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));core.require(e,actor,false);return counts(id);
    }
    private ParticipantDto.Counts counts(UUID id){var c=core.counts(id);return new ParticipantDto.Counts(c.registered(),c.eligible(),c.revoked());}

    @Transactional(readOnly=true)
    public ParticipantDto.Mine mine(UUID id,UUID actor) {
        Exam e=exams.findById(id).orElseThrow(()->ExamCore.error(404,"Exam not found"));
        var row=participants.findByExamIdAndUserId(id,actor).orElseThrow(()->ExamCore.error(404,"NOT_A_PARTICIPANT"));
        if(internalToken.isBlank())throw ExamCore.error(503,"Session integration not configured");
        var attempts=sessions.attempts(id,actor,internalToken).stream().map(a->new ParticipantDto.AttemptView(a.sessionId(),a.attemptNumber(),a.status(),a.startedAt(),a.endedAt(),a.expiresAt(),a.invalidated(),a.invalidatedAt(),a.invalidationReason())).toList();
        int effective=e.getAttemptLimit()+row.getExtraAttempts();long used=attempts.size();
        boolean active=attempts.stream().anyMatch(a->"IN_PROGRESS".equals(a.status()));
        var history=grants.findByExamIdAndUserIdOrderByCreatedAtAsc(id,actor).stream().map(g->new ParticipantDto.GrantView(g.getId(),g.getQuantity(),g.getReason(),g.getCreatedAt())).toList();
        return new ParticipantDto.Mine(id,row.getStatus().name(),row.getSource(),row.getCreatedAt(),row.isActive()?null:row.getStatusChangedAt(),row.isActive()?null:row.getRevocationReason(),
                e.getAttemptLimit(),row.getExtraAttempts(),effective,used,Math.max(0,effective-used),active,e.getFollowers().containsKey(actor),attempts,history);
    }

    /** One identity call and one attempt-summary call for the whole page: no per-row requests. */
    private List<ParticipantDto.Row> rows(Exam e,List<ExamParticipant> page) {
        if(page.isEmpty())return List.of();
        List<UUID> ids=page.stream().map(ExamParticipant::getUserId).toList();
        Map<UUID,SessionClient.AttemptSummary> summary=new HashMap<>();
        if(internalToken.isBlank())throw ExamCore.error(503,"Session integration not configured");
        sessions.attemptSummary(e.getId(),ids,internalToken).forEach(v->summary.put(v.userId(),v));
        Map<UUID,IdentityClient.Identity> names=new HashMap<>();
        try{resolve(new IdentityClient.Selector(ids,null),false).forEach(v->names.put(v.userId(),v));}catch(RuntimeException unavailable){/* display names are optional on the roster */}
        return page.stream().map(p->{
            var s=summary.get(p.getUserId());var n=names.get(p.getUserId());int effective=e.getAttemptLimit()+p.getExtraAttempts();
            long used=s==null?0:s.used();
            var info=new ParticipantDto.AttemptInfo(used,s==null?0:s.valid(),s==null?0:s.invalidated(),Math.max(0,effective-used),s!=null&&s.active(),s==null?null:s.latestSessionId(),s==null?null:s.latestAttemptNumber(),s==null?null:s.latestStatus());
            return new ParticipantDto.Row(p.getUserId(),n==null?null:n.displayName(),n==null?null:n.email(),p.getSource(),p.getStatus().name(),p.getCreatedAt(),p.getAddedBy(),p.getStatusChangedAt(),p.getStatusChangedBy(),p.getRevocationReason(),p.getExtraAttempts(),e.getAttemptLimit(),effective,info);
        }).toList();
    }

    /** Trusted best-effort display data for Exam-owned consumers; authorization stays with the caller route. */
    public IdentityClient.Identity identity(UUID userId) {
        if(userId==null)throw ExamCore.error(400,"User required");
        return resolve(new IdentityClient.Selector(List.of(userId),null),false).stream().filter(i->userId.equals(i.userId())).findFirst().orElse(null);
    }

    private List<IdentityClient.Identity> resolve(IdentityClient.Selector selector,boolean required) {
        if(identityToken.isBlank())throw ExamCore.error(503,"IDENTITY_PROVIDER_NOT_CONFIGURED");
        try{
            var found=identities.resolve(selector,identityToken);
            return found==null?List.of():found.stream().filter(Objects::nonNull).filter(i->i.userId()!=null).collect(Collectors.toList());
        }catch(RuntimeException failure){
            // Fail closed: never assign without a verified Student identity.
            throw ExamCore.error(503,"IDENTITY_PROVIDER_UNAVAILABLE");
        }
    }
}
