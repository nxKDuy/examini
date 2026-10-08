package com.datn.examsession.service;
import com.datn.examsession.entity.ExamSession;
import com.datn.examsession.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
@Component @RequiredArgsConstructor @EnableScheduling
public class SessionMaintenance {
    private final SessionService service;private final SessionRepository sessions;
    private final SubmissionEventRepository events;private final RabbitTemplate rabbit;private final AttemptEventRepository attemptEvents;private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SessionMaintenance.class);
    @Scheduled(fixedDelayString="${exam.timeout-sweep-ms:1000}") public void expire(){for(var s:sessions.findByStatusAndExpiresAtLessThanEqual(ExamSession.Status.IN_PROGRESS,Instant.now()))service.autoSubmitExam(s.getId());}
    @Scheduled(fixedDelayString="${exam.outbox-poll-ms:2000}") public void deliver(){
        for(var e:events.findTop100ByDeliveredFalse())try{
            var correlation=new CorrelationData(e.getSessionId().toString());
            rabbit.convertAndSend("","exam.submitted.queue",e.getSessionId().toString(),correlation);
            var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
            if(!confirm.isAck() || correlation.getReturned()!=null)throw new IllegalStateException("Broker rejected event");
            e.setDelivered(true);events.save(e);
        }catch(Exception failure){log.warn("Submission event delivery pending for session {}",e.getSessionId());}
    }

    /** Attempt events: queue for Grading (direct) and the shared topic exchange for downstream consumers. Each leg is confirmed independently. */
    @Scheduled(fixedDelayString="${exam.outbox-poll-ms:2000}") @org.springframework.transaction.annotation.Transactional public void deliverAttemptEvents(){
        for(var e:attemptEvents.findTop100ByGradingDeliveredFalseOrPublicDeliveredFalseOrderByOccurredAtAsc()){
            if(!e.isGradingDelivered())try{
                var correlation=new CorrelationData(e.getEventId()+":grading");
                rabbit.convertAndSend("","exam.attempt.invalidated.queue",e.getPayload(),correlation);
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Broker rejected event");
                e.markGradingDelivered();
            }catch(Exception failure){log.warn("Attempt event grading leg pending for {}",e.getEventId());}
            if(!e.isPublicDelivered())try{
                var envelope=new java.util.LinkedHashMap<String,Object>();
                envelope.put("eventId",e.getEventId());envelope.put("type",e.getEventType());envelope.put("version",1);
                envelope.put("occurredAt",e.getOccurredAt().toString());envelope.put("payload",json.readTree(e.getPayload()));
                var props=new org.springframework.amqp.core.MessageProperties();
                props.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
                props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);props.setMessageId(e.getEventId().toString());
                var correlation=new CorrelationData(e.getEventId()+":public");
                rabbit.send("examini.events",e.getEventType(),new org.springframework.amqp.core.Message(json.writeValueAsBytes(envelope),props),correlation);
                var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
                if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Event was not confirmed");
                e.markPublicDelivered();
            }catch(Exception failure){log.warn("Attempt event public leg pending for {}",e.getEventId());}
        }
    }
}
