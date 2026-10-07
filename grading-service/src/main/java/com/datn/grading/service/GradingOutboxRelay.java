package com.datn.grading.service;

import com.datn.grading.repository.GradingOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor
public class GradingOutboxRelay {
    private final GradingOutboxRepository outbox;private final RabbitTemplate rabbit;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GradingOutboxRelay.class);
    @Scheduled(fixedDelayString="${grading.events.outbox-poll-ms:1000}",initialDelayString="${grading.events.outbox-initial-delay-ms:1000}")
    @Transactional public void relay(){
        for(var event:outbox.findTop100ByDeliveredFalseOrderByOccurredAtAsc())try{
            event.attempted();var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setContentEncoding(StandardCharsets.UTF_8.name());props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(event.getEventId().toString());
            var correlation=new CorrelationData(event.getEventId().toString());
            rabbit.send(event.getExchangeName(),event.getRoutingKey(),new Message(event.getPayload().getBytes(StandardCharsets.UTF_8),props),correlation);
            var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Event was not confirmed");
            event.delivered();
        }catch(Exception failure){log.warn("Grading event remains pending for eventId={}",event.getEventId());}
    }
}
