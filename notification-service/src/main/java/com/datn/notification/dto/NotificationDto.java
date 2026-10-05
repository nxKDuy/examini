package com.datn.notification.dto;
import com.datn.notification.entity.Notification;
import java.time.Instant;
import java.util.*;
public final class NotificationDto {
    private NotificationDto(){}
    public record NotificationResponse(UUID id,String type,String title,String message,String entityType,UUID entityId,Map<String,String> metadata,Instant createdAt,Instant readAt,boolean read){
        public static NotificationResponse of(Notification n){return new NotificationResponse(n.getId(),n.getType(),n.getTitle(),n.getMessage(),n.getEntityType(),n.getEntityId(),n.getMetadata(),n.getCreatedAt(),n.getReadAt(),n.getReadAt()!=null);}
    }
    public record UnreadCount(long count){}
    public record ReadAllResult(int updated){}
    /** Notification derived from a source-domain event; one recipient per event. */
    public record Draft(UUID recipient,String type,String title,String message,String entityType,UUID entityId,Map<String,String> metadata){}
}
