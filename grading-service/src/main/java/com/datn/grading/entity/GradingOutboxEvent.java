package com.datn.grading.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="grading_event_outbox",uniqueConstraints=@UniqueConstraint(name="uq_grading_event_revision_route",columnNames={"result_id","result_revision","routing_key"}),indexes=@Index(name="idx_grading_outbox_pending",columnList="delivered,occurred_at"))
@Getter @NoArgsConstructor
public class GradingOutboxEvent {
    @Id private UUID eventId;
    @Column(name="result_id",nullable=false) private UUID resultId;
    @Column(name="result_revision",nullable=false) private long resultRevision;
    @Column(name="exchange_name",nullable=false,length=80) private String exchangeName;
    @Column(name="routing_key",nullable=false,length=80) private String routingKey;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(nullable=false,columnDefinition="jsonb") private String payload;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(nullable=false) private boolean delivered;
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    public GradingOutboxEvent(UUID eventId,UUID resultId,long revision,String exchange,String routing,String payload,Instant occurredAt){this.eventId=eventId;this.resultId=resultId;this.resultRevision=revision;this.exchangeName=exchange;this.routingKey=routing;this.payload=payload;this.occurredAt=occurredAt;}
    public void attempted(){attemptCount++;}
    public void delivered(){delivered=true;}
}
