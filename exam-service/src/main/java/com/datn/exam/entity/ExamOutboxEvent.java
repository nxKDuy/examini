package com.datn.exam.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="exam_event_outbox") @Getter @NoArgsConstructor
public class ExamOutboxEvent {
    @Id private UUID eventId;
    @Column(nullable=false,length=64) private String eventType;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable=false,columnDefinition="jsonb") private String payload;
    @Column(nullable=false) private Instant occurredAt;
    @Column(nullable=false) private boolean delivered;

    public ExamOutboxEvent(UUID eventId,String eventType,String payload,Instant occurredAt) {
        this.eventId=eventId;this.eventType=eventType;this.payload=payload;this.occurredAt=occurredAt;this.delivered=false;
    }
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="last_error",length=500) private String lastError;
    @Column(nullable=false) private boolean parked;
    public void markDelivered(){delivered=true;nextAttemptAt=null;lastError=null;}
    public void attempted(){attemptCount++;}
    /** Failed attempt: skipped until nextAttempt, or for good once parked (reset parked=false to replay). */
    public void failed(String error,Instant nextAttempt,boolean park){lastError=error;nextAttemptAt=nextAttempt;parked=park;}
}
