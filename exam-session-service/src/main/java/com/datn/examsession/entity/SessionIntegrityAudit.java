package com.datn.examsession.entity;

import com.datn.examsession.dto.IntegrityDto;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="session_integrity_audit") @Immutable
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SessionIntegrityAudit {
    @Id private UUID id;
    @Column(name="session_id",nullable=false,updatable=false) private UUID sessionId;
    @Column(name="exam_id",nullable=false,updatable=false) private UUID examId;
    @Column(name="actor_user_id",updatable=false) private UUID actorUserId;
    @Column(name="actor_type",nullable=false,updatable=false,length=16) private String actorType;
    @Column(nullable=false,updatable=false,length=64) private String action;
    @Column(name="trigger_event_id",updatable=false) private UUID triggerEventId;
    @Column(name="event_count",updatable=false) private Long eventCount;
    @Column(name="occurred_at",nullable=false,updatable=false) private Instant occurredAt;
    @Column(columnDefinition="TEXT",updatable=false) private String metadata;
    public SessionIntegrityAudit(ExamSession session,UUID actor,String actorType,String action,UUID eventId,Long count,Instant at,String metadata) {
        id=UUID.randomUUID();sessionId=session.getId();examId=session.getExamId();actorUserId=actor;
        this.actorType=actorType;this.action=action;triggerEventId=eventId;eventCount=count;occurredAt=at;this.metadata=metadata;
    }
    public IntegrityDto.Audit dto(){return new IntegrityDto.Audit(id,examId,sessionId,actorUserId,actorType,action,triggerEventId,eventCount,occurredAt,metadata);}
}
