package com.datn.examsession.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

/** Transactional outbox row for attempt events owned by this service. */
@Entity @Table(name="attempt_events") @Getter @NoArgsConstructor
public class AttemptEvent {
    @Id private UUID eventId;
    @Column(nullable=false,length=64) private String eventType;
    @Column(nullable=false) private UUID sessionId;
    @Column(nullable=false,columnDefinition="TEXT") private String payload;
    @Column(nullable=false) private Instant occurredAt;
    @Column(nullable=false) private boolean gradingDelivered;
    @Column(nullable=false) private boolean publicDelivered;
    public AttemptEvent(UUID eventId,String eventType,UUID sessionId,String payload,Instant occurredAt){
        this.eventId=eventId;this.eventType=eventType;this.sessionId=sessionId;this.payload=payload;this.occurredAt=occurredAt;
    }
    /** Public-only event: Grading's point-to-point queue carries invalidations only. */
    public static AttemptEvent publicOnly(UUID eventId,String eventType,UUID sessionId,String payload,Instant occurredAt){
        var e=new AttemptEvent(eventId,eventType,sessionId,payload,occurredAt);e.gradingDelivered=true;return e;
    }
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    @Column(name="next_attempt_at") private java.time.Instant nextAttemptAt;
    @Column(name="last_error",length=500) private String lastError;
    @Column(nullable=false) private boolean parked;
    public void attempted(){attemptCount++;}
    /** Failed attempt: skipped until nextAttempt, or for good once parked (reset parked=false to replay). */
    public void failed(String error,java.time.Instant nextAttempt,boolean park){lastError=error;nextAttemptAt=nextAttempt;parked=park;}
    public void retryCleared(){nextAttemptAt=null;lastError=null;}
    public void markGradingDelivered(){gradingDelivered=true;}
    public void markPublicDelivered(){publicDelivered=true;}
}
