package com.datn.examsession.service;
import com.datn.examsession.entity.AttemptEvent;
import com.datn.examsession.entity.ExamSession;
import com.datn.examsession.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.time.Instant;
@Component @RequiredArgsConstructor @EnableScheduling
public class SessionMaintenance {
    private final SessionService service;private final SessionRepository sessions;
    private final SubmissionEventRepository events;private final RabbitTemplate rabbit;private final AttemptEventRepository attemptEvents;private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SessionMaintenance.class);
    @Scheduled(fixedDelayString="${exam.timeout-sweep-ms:1000}") public void expire(){for(var s:sessions.findByStatusAndExpiresAtLessThanEqual(ExamSession.Status.IN_PROGRESS,Instant.now()))service.autoSubmitExam(s.getId());}
    @org.springframework.beans.factory.annotation.Value("${exam.events.retry-base-ms:2000}") private long retryBaseMs=2000;
    @org.springframework.beans.factory.annotation.Value("${exam.events.retry-max-ms:900000}") private long retryMaxMs=900000;
    @org.springframework.beans.factory.annotation.Value("${exam.events.max-attempts:200}") private int maxAttempts=200;

    @Scheduled(fixedDelayString="${exam.outbox-poll-ms:2000}") @org.springframework.transaction.annotation.Transactional public void deliver(){deliver(Instant.now());}
    /** Submission events: point-to-point exam.submitted.queue; failures back off and park (same rules as grading). */
    @org.springframework.transaction.annotation.Transactional public void deliver(Instant now){
        for(var e:events.findDue(now,org.springframework.data.domain.PageRequest.of(0,100))){
            e.attempted();
            try{
                var correlation=new CorrelationData(e.getSessionId().toString());
                rabbit.convertAndSend("","exam.submitted.queue",e.getSessionId().toString(),correlation);
                OutboxRetry.confirmed(correlation);
                e.setDelivered(true);e.retryCleared();
            }catch(Exception failure){
                String reason=OutboxRetry.truncate(OutboxRetry.reason(failure));
                boolean park=e.getAttemptCount()>=maxAttempts;Instant next=park?null:next(now,e.getAttemptCount());
                e.failed(reason,next,park);report(park,"exam.submitted",e.getSessionId(),e.getAttemptCount(),next,reason);
            }
            events.save(e);
        }
    }

    /** Attempt events: queue for Grading (direct) and the shared topic exchange for downstream consumers. Each leg is confirmed independently. */
    @Scheduled(fixedDelayString="${exam.outbox-poll-ms:2000}") @org.springframework.transaction.annotation.Transactional public void deliverAttemptEvents(){deliverAttemptEvents(Instant.now());}
    @org.springframework.transaction.annotation.Transactional public void deliverAttemptEvents(Instant now){
        for(var e:attemptEvents.findDue(now,org.springframework.data.domain.PageRequest.of(0,100))){
            e.attempted();String reason=null;
            if(!e.isGradingDelivered())try{
                var correlation=new CorrelationData(e.getEventId()+":grading");
                rabbit.convertAndSend("","exam.attempt.invalidated.queue",e.getPayload(),correlation);
                OutboxRetry.confirmed(correlation);
                e.markGradingDelivered();
            }catch(Exception failure){reason="grading leg "+OutboxRetry.reason(failure);}
            if(!e.isPublicDelivered())try{
                var envelope=envelope(e,json);
                var props=new org.springframework.amqp.core.MessageProperties();
                props.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
                props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);props.setMessageId(e.getEventId().toString());
                var correlation=new CorrelationData(e.getEventId()+":public");
                rabbit.send("examini.events",e.getEventType(),new org.springframework.amqp.core.Message(json.writeValueAsBytes(envelope),props),correlation);
                OutboxRetry.confirmed(correlation);
                e.markPublicDelivered();
            }catch(Exception failure){reason=(reason==null?"":reason+"; ")+"public leg "+OutboxRetry.reason(failure);}
            if(reason==null){e.retryCleared();continue;}
            reason=OutboxRetry.truncate(reason);
            boolean park=e.getAttemptCount()>=maxAttempts;Instant next=park?null:next(now,e.getAttemptCount());
            e.failed(reason,next,park);report(park,e.getEventType(),e.getEventId(),e.getAttemptCount(),next,reason);
        }
    }
    private Instant next(Instant now,int attempts){return now.plusMillis(OutboxRetry.backoffMs(attempts,retryBaseMs,retryMaxMs));}
    private void report(boolean park,String type,java.util.UUID id,int attempts,Instant next,String reason){
        if(park)log.error("Session event parked after {} attempts id={} eventType={} reason={}",attempts,id,type,reason);
        else log.warn("Session event delivery failed attempt={} id={} eventType={} nextAttemptAt={} reason={}",attempts,id,type,next,reason);
    }
    /** Shared examini.events envelope: {eventId,eventType,eventVersion,occurredAt,payload} (same as exam-service and grading). */
    static java.util.Map<String,Object> envelope(AttemptEvent e,com.fasterxml.jackson.databind.ObjectMapper json)throws com.fasterxml.jackson.core.JsonProcessingException{
        var envelope=new java.util.LinkedHashMap<String,Object>();
        envelope.put("eventId",e.getEventId());envelope.put("eventType",e.getEventType());envelope.put("eventVersion",1);
        envelope.put("occurredAt",e.getOccurredAt().toString());envelope.put("payload",json.readTree(e.getPayload()));
        return envelope;
    }
}
