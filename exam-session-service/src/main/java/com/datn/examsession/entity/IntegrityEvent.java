package com.datn.examsession.entity;

import com.datn.examsession.dto.IntegrityDto.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/** Append-only browser evidence. It cannot alter session state, submission or grading. */
@Entity @Table(name="integrity_events") @Immutable @Getter
@NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class IntegrityEvent {
    @Id private UUID id;
    @Column(name="client_event_id",nullable=false,updatable=false) private UUID clientEventId;
    @Column(name="exam_id",nullable=false,updatable=false) private UUID examId;
    @Column(name="session_id",nullable=false,updatable=false) private UUID sessionId;
    @Column(name="user_id",nullable=false,updatable=false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,updatable=false,length=32) private EventType type;
    @Column(name="occurred_at",updatable=false) private Instant occurredAt;
    @Column(name="received_at",nullable=false,updatable=false) private Instant receivedAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,updatable=false,length=16) private Severity severity;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",updatable=false) private Metadata metadata;
    @Column(nullable=false,updatable=false,length=16) private String source;
    public IntegrityEvent(ExamSession session, com.datn.examsession.dto.IntegrityDto.Ingest input, Instant now) {
        id=UUID.randomUUID();clientEventId=input.clientEventId();examId=session.getExamId();
        sessionId=session.getId();userId=session.getUserId();type=input.type();
        occurredAt=input.occurredAt();receivedAt=now;metadata=input.metadata();source="BROWSER";
        severity=type==EventType.EXIT_FULLSCREEN?Severity.MEDIUM:Severity.LOW;
    }
    public com.datn.examsession.dto.IntegrityDto.Event dto() {
        return new com.datn.examsession.dto.IntegrityDto.Event(id,clientEventId,examId,sessionId,userId,
                type,occurredAt,receivedAt,severity,metadata,source);
    }
}
