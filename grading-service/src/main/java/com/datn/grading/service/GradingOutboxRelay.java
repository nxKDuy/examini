package com.datn.grading.service;

import com.datn.grading.entity.GradingOutboxEvent;
import com.datn.grading.repository.GradingOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Delivers outbox rows with publisher confirms. A failed attempt (broker down, nack, confirm timeout,
 * or a mandatory return because no queue is bound to the routing key) backs off exponentially and the
 * row parks after grading.events.max-attempts; reset parked=false to replay it.
 */
@Component @RequiredArgsConstructor
public class GradingOutboxRelay {
    private final GradingOutboxRepository outbox;private final RabbitTemplate rabbit;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GradingOutboxRelay.class);
    @Value("${grading.events.retry-base-ms:2000}") private long retryBaseMs=2000;
    @Value("${grading.events.retry-max-ms:900000}") private long retryMaxMs=900000;
    @Value("${grading.events.max-attempts:200}") private int maxAttempts=200;

    @Scheduled(fixedDelayString="${grading.events.outbox-poll-ms:1000}",initialDelayString="${grading.events.outbox-initial-delay-ms:1000}")
    @Transactional public void relay(){relay(Instant.now());}

    @Transactional public void relay(Instant now){
        for(var event:outbox.findDue(now,PageRequest.of(0,100))){
            event.attempted();
            try{send(event);event.delivered();}
            catch(Exception failure){
                String reason=failure instanceof DeliveryFailure?failure.getMessage():failure.getClass().getSimpleName()+": "+failure.getMessage();
                boolean park=event.getAttemptCount()>=maxAttempts;
                Instant next=park?null:now.plusMillis(backoffMs(event.getAttemptCount()));
                event.failed(reason,next,park);
                if(park)log.error("Grading event parked after {} attempts eventId={} exchange={} routingKey={} reason={}",event.getAttemptCount(),event.getEventId(),event.getExchangeName(),event.getRoutingKey(),reason);
                else log.warn("Grading event delivery failed attempt={} eventId={} exchange={} routingKey={} nextAttemptAt={} reason={}",event.getAttemptCount(),event.getEventId(),event.getExchangeName(),event.getRoutingKey(),next,reason);
            }
        }
    }
    long backoffMs(int attempts){
        int shift=Math.min(Math.max(attempts-1,0),30);
        return Math.min(retryMaxMs,retryBaseMs*(1L<<shift));
    }
    private void send(GradingOutboxEvent event)throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setContentEncoding(StandardCharsets.UTF_8.name());props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(event.getEventId().toString());
        var correlation=new CorrelationData(event.getEventId().toString());
        rabbit.send(event.getExchangeName(),event.getRoutingKey(),new Message(event.getPayload().getBytes(StandardCharsets.UTF_8),props),correlation);
        CorrelationData.Confirm confirm;
        try{confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException timeout){throw new DeliveryFailure("CONFIRM_TIMEOUT: broker did not confirm within 5s");}
        var returned=correlation.getReturned();
        // Mandatory publish: the broker accepted it but no queue is bound to this exchange/routing key.
        if(returned!=null)throw new DeliveryFailure("UNROUTABLE: "+returned.getReplyCode()+" "+returned.getReplyText()+" (no queue bound to "+returned.getExchange()+"/"+returned.getRoutingKey()+")");
        if(confirm==null||!confirm.isAck())throw new DeliveryFailure("NACK: "+(confirm==null?"no confirm":confirm.getReason()));
    }
    static final class DeliveryFailure extends Exception{DeliveryFailure(String message){super(message);}}
}
