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
    private final SubmissionEventRepository events;private final RabbitTemplate rabbit;
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
}
