package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.dto.IntegrityDto.*;
import com.datn.examsession.dto.IntegrityPolicy;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional
public class IntegrityService {
    private final SessionRepository sessions;
    private final IntegrityEventRepository events;
    private final SessionIntegrityAuditRepository audits;
    private final ExamClient exams;
    private final EntityManager em;
    private final SessionService sessionService;
    @Value("${exam.internal-token:}") private String token;
    @Value("${exam.integrity.events-per-minute:120}") private int perMinute;
    @Value("${exam.integrity.max-events-per-session:10000}") private int maxEvents;
    @Value("${exam.integrity.leave-coalesce-ms:1500}") private long coalesceMs;
    // Network loss is audit-only; SESSION_RESUME and recovery signals are evidence without budget cost.
    private static final Set<EventType> BUDGET_EVENTS=Set.of(EventType.TAB_SWITCH,EventType.WINDOW_BLUR,
            EventType.EXIT_FULLSCREEN,EventType.PAGE_RELOAD);
    // One leave transition (tab hidden, window blur and the fullscreen exit it causes) is one unit; PAGE_RELOAD stays separate.
    private static final Set<EventType> LEAVE_EVENTS=Set.of(EventType.TAB_SWITCH,EventType.WINDOW_BLUR,EventType.EXIT_FULLSCREEN);
    @jakarta.annotation.PostConstruct void validateConfiguration() {
        if(perMinute<1 || maxEvents<1 || coalesceMs<0)throw new IllegalStateException("Integrity limits must be positive");
    }
    private static ResponseStatusException error(int status,String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status),message);
    }
    private ExamSession find(UUID id) {
        return sessions.findById(id).orElseThrow(()->error(404,"Session not found"));
    }
    private void review(UUID exam,UUID actor,String actorRole) {
        if(!Set.of("TEACHER","ADMIN").contains(actorRole))throw error(403,"Private integrity review requires exam staff");
        if(token==null || token.isBlank())throw error(503,"Exam permission service not configured");
        String permission=exams.permission(exam,actor,false,token);
        if(!Set.of("OWNER","MANAGER","PROCTOR").contains(permission==null?"":permission))
            throw error(403,"Exam integrity review permission denied");
    }
    public IntegrityPolicy policy(UUID session,UUID actor,String actorRole) {
        var s=find(session);
        if(!s.getUserId().equals(actor))review(s.getExamId(),actor,actorRole);
        return s.getIntegrityPolicy();
    }
    public Receipt ingest(UUID sessionId,UUID actor,String actorRole,Ingest input) {
        // The session owner acts as a participant even with a Teacher persona (PUBLIC Phase 1).
        if(!Set.of("STUDENT","TEACHER","ADMIN").contains(actorRole))throw error(403,"Authenticated participant required");
        // One short row lock serializes ingestion, deduplication and rate checks across instances.
        var s=sessions.lockById(sessionId).orElseThrow(()->error(404,"Session not found"));
        em.refresh(s);
        if(!s.getUserId().equals(actor))throw error(403,"Not your session");
        validate(input);
        var existing=events.findBySessionIdAndClientEventId(sessionId,input.clientEventId());
        if(existing.isPresent()) {
            var e=existing.get();
            if(e.getType()!=input.type() || !Objects.equals(e.getOccurredAt(),input.occurredAt()) || !Objects.equals(e.getMetadata(),input.metadata()))
                throw error(409,"clientEventId already used for different evidence");
            long warningCount=events.countBySessionIdAndCountedTrue(sessionId);
            return receipt(e,s.getIntegrityPolicy(),true,warningCount,s.getStatus()==ExamSession.Status.AUTO_SUBMITTED);
        }
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        boolean active=s.getStatus()==ExamSession.Status.IN_PROGRESS && now.isBefore(s.getExpiresAt());
        if(!enabled(s.getIntegrityPolicy(),input.type()))throw error(409,"Signal disabled by frozen integrity policy");
        // Client time is untrusted evidence only.
        if(input.occurredAt()!=null && (input.occurredAt().isBefore(now.minusSeconds(604800)) || input.occurredAt().isAfter(now.plusSeconds(604800))))
            throw error(400,"Client evidence time outside supported seven-day range");
        if(events.countBySessionId(sessionId)>=maxEvents || events.countBySessionIdAndReceivedAtAfter(sessionId,now.minusSeconds(60))>=perMinute)
            throw error(429,"Integrity event limit reached; core exam actions remain available");
        Instant started=s.getStartTime()==null?null:s.getStartTime().toInstant(java.time.ZoneOffset.UTC);
        // Budget timing uses only the server receipt time; client occurredAt is stored as evidence and can
        // only mark a signal STALE (before Start), never merge a new leave into an earlier counted unit.
        Instant effective=now;
        NotCountedReason reason=null;UUID into=null;
        if(!BUDGET_EVENTS.contains(input.type()))reason=NotCountedReason.NOT_BUDGET_SIGNAL;
        else if(!active || input.occurredAt()!=null && started!=null && input.occurredAt().isBefore(started))reason=NotCountedReason.STALE;
        else if(LEAVE_EVENTS.contains(input.type())) {
            var unit=events.findFirstBySessionIdAndCountedTrueAndTypeInAndEffectiveAtBetweenOrderByEffectiveAtAsc(
                    sessionId,LEAVE_EVENTS,effective.minusMillis(coalesceMs),effective.plusMillis(coalesceMs));
            if(unit.isPresent()){reason=NotCountedReason.COALESCED;into=unit.get().getId();}
        }
        var e=events.saveAndFlush(new IntegrityEvent(s,input,now,effective,reason,into));
        long warningCount=events.countBySessionIdAndCountedTrue(sessionId);
        int warningLimit=s.getIntegrityPolicy().warningEventLimit();
        boolean autoSubmitted=e.isCounted() && warningCount>warningLimit &&
                sessionService.autoSubmitForIntegrity(s,e.getId(),warningCount);
        return receipt(e,s.getIntegrityPolicy(),false,warningCount,autoSubmitted);
    }
    private static boolean enabled(IntegrityPolicy p,EventType type) {
        return switch(type) {
            case TAB_SWITCH -> p.monitorTabSwitch();
            case WINDOW_BLUR,WINDOW_FOCUS -> p.monitorWindowBlur();
            case EXIT_FULLSCREEN,FULLSCREEN_ENTER -> p.requireFullscreen();
            case COPY -> p.monitorCopy();
            case PASTE -> p.monitorPaste();
            case NETWORK_DISCONNECT,NETWORK_RECONNECT -> p.monitorNetwork();
            // Recovery/navigation evidence has no separate product toggle. It is collected only
            // when this frozen attempt has at least one browser monitor enabled.
            case PAGE_RELOAD,SESSION_RESUME -> monitored(p);
        };
    }
    private static boolean monitored(IntegrityPolicy p) {
        return p.monitorTabSwitch() || p.monitorWindowBlur() || p.requireFullscreen() ||
            p.monitorCopy() || p.monitorPaste() || p.monitorNetwork();
    }
    private static void validate(Ingest input) {
        if(input==null || input.clientEventId()==null || input.type()==null)throw error(400,"clientEventId and type required");
        var m=input.metadata();if(m==null)return;
        if(m.durationMs()!=null && (m.durationMs()<0 || m.durationMs()>86400000))throw error(400,"Invalid durationMs");
        boolean window=Set.of(EventType.TAB_SWITCH,EventType.WINDOW_BLUR,EventType.WINDOW_FOCUS).contains(input.type());
        boolean fullscreen=Set.of(EventType.EXIT_FULLSCREEN,EventType.FULLSCREEN_ENTER).contains(input.type());
        boolean network=Set.of(EventType.NETWORK_DISCONNECT,EventType.NETWORK_RECONNECT).contains(input.type());
        if((m.visibilityState()!=null && !window) || ((m.fromFullscreen()!=null || m.toFullscreen()!=null) && !fullscreen) ||
           (m.networkState()!=null && !network) || (m.durationMs()!=null && !(window || fullscreen || network)))
            throw error(400,"Metadata not allowed for this signal");
        if((input.type()==EventType.NETWORK_DISCONNECT && m.networkState()==NetworkState.ONLINE) ||
           (input.type()==EventType.NETWORK_RECONNECT && m.networkState()==NetworkState.OFFLINE) ||
           (input.type()==EventType.EXIT_FULLSCREEN && Boolean.TRUE.equals(m.toFullscreen())) ||
           (input.type()==EventType.FULLSCREEN_ENTER && Boolean.FALSE.equals(m.toFullscreen())))
            throw error(400,"Metadata contradicts signal type");
    }
    private static Receipt receipt(IntegrityEvent e,IntegrityPolicy policy,boolean duplicate,long warningCount,boolean autoSubmitted) {
        int limit=policy.warningEventLimit();boolean warn=!duplicate && e.isCounted() && warningCount==limit && !autoSubmitted;
        return new Receipt(e.dto(),duplicate,warningCount,limit,warn,
                warn?"You have reached the browser integrity warning limit; signals are evidence and are not individual proof of cheating.":null,autoSubmitted,
                e.isCounted(),e.getNotCountedReason());
    }
    private static Pageable page(int page,int size) {
        if(page<0 || size<1 || size>100)throw error(400,"page must be nonnegative; size must be 1..100");
        return PageRequest.of(page,size,Sort.by("receivedAt","id"));
    }
    @Transactional(readOnly=true)
    public Page<Event> examEvents(UUID exam,UUID actor,String role,EventType type,int page,int size) {
        review(exam,actor,role);return events.search(exam,null,type,page(page,size)).map(IntegrityEvent::dto);
    }
    @Transactional(readOnly=true)
    public Page<Event> sessionEvents(UUID session,UUID actor,String role,EventType type,int page,int size) {
        var s=find(session);review(s.getExamId(),actor,role);return events.search(s.getExamId(),session,type,page(page,size)).map(IntegrityEvent::dto);
    }
    @Transactional(readOnly=true)
    public Summary summary(UUID session,UUID actor,String role) {
        var s=find(session);review(s.getExamId(),actor,role);
        var counts=new EnumMap<EventType,Long>(EventType.class);
        for(var type:EventType.values())counts.put(type,0L);
        events.counts(s.getExamId(),session).forEach(c->counts.put(c.getType(),c.getTotal()));
        return new Summary(s.getExamId(),session,counts.values().stream().mapToLong(Long::longValue).sum(),Collections.unmodifiableMap(counts));
    }
    @Transactional(readOnly=true)
    public Page<Audit> audit(UUID session,UUID actor,String role,int page,int size) {
        var s=find(session);review(s.getExamId(),actor,role);
        if(page<0 || size<1 || size>100)throw error(400,"page must be nonnegative; size must be 1..100");
        return audits.findBySessionId(session,PageRequest.of(page,size,Sort.by("occurredAt","id"))).map(SessionIntegrityAudit::dto);
    }
}
