package com.datn.notification.repository;
import com.datn.notification.entity.Notification;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface NotificationRepository extends JpaRepository<Notification,UUID> {
    /** Returns 0 when this event already produced the recipient's notification. */
    @Modifying @Query(nativeQuery=true,value="""
        INSERT INTO notifications(id,recipient_user_id,type,title,message,entity_type,entity_id,metadata,source_event_id,source_event_type)
        VALUES(:id,:recipient,:type,:title,:message,:entityType,:entityId,CAST(:metadata AS jsonb),:eventId,:eventType)
        ON CONFLICT(source_event_id,recipient_user_id) DO NOTHING""")
    int insertOnce(@Param("id")UUID id,@Param("recipient")UUID recipient,@Param("type")String type,@Param("title")String title,@Param("message")String message,
                   @Param("entityType")String entityType,@Param("entityId")UUID entityId,@Param("metadata")String metadata,@Param("eventId")UUID eventId,@Param("eventType")String eventType);
    @Query("""
        select n from Notification n where n.recipientUserId=:recipient
        and (:read is null or (:read=true and n.readAt is not null) or (:read=false and n.readAt is null))
        order by n.createdAt desc, n.id desc""")
    Page<Notification> inbox(@Param("recipient")UUID recipient,@Param("read")Boolean read,Pageable page);
    long countByRecipientUserIdAndReadAtIsNull(UUID recipient);
    Optional<Notification> findByIdAndRecipientUserId(UUID id,UUID recipient);
    @Modifying(clearAutomatically=true) @Query("update Notification n set n.readAt=CURRENT_TIMESTAMP where n.id=:id and n.recipientUserId=:recipient and n.readAt is null")
    int markRead(@Param("id")UUID id,@Param("recipient")UUID recipient);
    @Modifying @Query("update Notification n set n.readAt=CURRENT_TIMESTAMP where n.recipientUserId=:recipient and n.readAt is null")
    int markAllRead(@Param("recipient")UUID recipient);
}
