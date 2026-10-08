package com.datn.notification.service;
import com.datn.notification.config.RabbitMQConfig;
import com.datn.notification.dto.NotificationDto.*;
import com.datn.notification.repository.NotificationRepository;
import com.datn.notification.grading.GradingNotifications;
import com.datn.notification.scheduling.SchedulingConsumer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
@Service @RequiredArgsConstructor @Transactional
public class NotificationService {
    private final NotificationRepository notifications;private final ObjectMapper json;private final SchedulingConsumer scheduling;private final GradingNotifications grading;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NotificationService.class);

    /** Envelope: {eventId, eventType, eventVersion: 1, occurredAt, payload}. Malformed events are dead-lettered. */
    @RabbitListener(queues=RabbitMQConfig.QUEUE)
    public void onEvent(byte[] body){
        EventCatalog.Event event;
        try{event=EventCatalog.envelope(json.readTree(new String(body,StandardCharsets.UTF_8)));}
        catch(IllegalArgumentException|JsonProcessingException invalid){
            log.warn("Rejected notification event: {}",invalid.getMessage());
            throw new AmqpRejectAndDontRequeueException("Invalid notification event",invalid);
        }
        // Infrastructure failures escape: broker retries then dead-letters, never silently dropped.
        // Same transaction as the listener, committed before the broker ack.
        switch(event.scheduling()){
            case SchedulingEvents.Schedule e -> scheduling.onSchedule(event.eventId(),e);
            case SchedulingEvents.Lifecycle e -> scheduling.onLifecycle(event.eventId(),e);
            case SchedulingEvents.Recipients e -> scheduling.onRecipients(e);
            case GradingEvents.Released e -> grading.onReleased(event.eventId(),e);
            case GradingEvents.Regraded e -> grading.onRegraded(e);
            case GradingEvents.ReviewRequired e -> grading.onReviewRequired(e);
            // A valid event without a notice (e.g. participant JOINED) has a null draft: consumed, nothing recorded.
            case null, default -> {if(event.draft()!=null)record(event.eventId(),event.eventType(),event.draft());}
        }
    }
    /** Idempotent: a redelivered event (same eventId) never creates a second notification. */
    public boolean record(UUID eventId,String eventType,Draft d){
        String metadata;try{metadata=json.writeValueAsString(d.metadata());}catch(JsonProcessingException e){throw new IllegalStateException(e);}
        return notifications.insertOnce(UUID.randomUUID(),d.recipient(),d.type(),d.title(),d.message(),d.entityType(),d.entityId(),metadata,eventId,eventType)==1;
    }
    @Transactional(readOnly=true)
    public Page<NotificationResponse> list(UUID actor,Boolean read,Pageable page){
        // Fixed newest-first order; client sort parameters are ignored.
        return notifications.inbox(actor,read,PageRequest.of(page.getPageNumber(),page.getPageSize())).map(NotificationResponse::of);
    }
    @Transactional(readOnly=true)
    public UnreadCount unread(UUID actor){return new UnreadCount(notifications.countByRecipientUserIdAndReadAtIsNull(actor));}
    public NotificationResponse markRead(UUID id,UUID actor){
        notifications.markRead(id,actor);
        // Foreign and missing IDs are indistinguishable: 404 either way.
        return notifications.findByIdAndRecipientUserId(id,actor).map(NotificationResponse::of).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found"));
    }
    public ReadAllResult markAllRead(UUID actor){return new ReadAllResult(notifications.markAllRead(actor));}
}
