package com.datn.notification.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;
/** In-app notification. Rows are inserted by NotificationRepository.insertOnce only (idempotent). */
@Entity @Table(name="notifications") @Getter @NoArgsConstructor
public class Notification {
    @Id private UUID id;
    @Column(name="recipient_user_id") private UUID recipientUserId;
    private String type;private String title;private String message;
    @Column(name="entity_type") private String entityType;
    @Column(name="entity_id") private UUID entityId;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String,String> metadata=new HashMap<>();
    @Column(name="source_event_id") private UUID sourceEventId;
    @Column(name="source_event_type") private String sourceEventType;
    @Column(name="created_at") private Instant createdAt;
    @Column(name="read_at") private Instant readAt;
}
