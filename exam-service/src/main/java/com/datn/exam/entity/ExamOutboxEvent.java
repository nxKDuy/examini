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
    public void markDelivered(){delivered=true;}
}
