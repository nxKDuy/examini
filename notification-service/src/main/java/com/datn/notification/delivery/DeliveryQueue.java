package com.datn.notification.delivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Queues the email and browser-push deliveries of one inbox notification (the in-app row is the inbox itself).
 * Push has one row per active subscription; no subscription is recorded as SKIPPED (never an email fallback).
 * Call inside the transaction that inserted the notification so inbox and deliveries commit together.
 */
@Component
public class DeliveryQueue {
    private final JdbcTemplate db;
    public DeliveryQueue(JdbcTemplate db){this.db=db;}

    public void enqueue(UUID notificationId,UUID userId,boolean email,Instant now,Instant expires){
        if(email)insert(notificationId,userId,"EMAIL",null,"PENDING",null,now,expires);
        List<UUID> subs=db.queryForList("SELECT id FROM push_subscriptions WHERE user_id=? AND revoked_at IS NULL",UUID.class,userId);
        if(subs.isEmpty())insert(notificationId,userId,"PUSH",null,"SKIPPED","NO_SUBSCRIPTION",now,expires);
        for(UUID sub:subs)insert(notificationId,userId,"PUSH",sub,"PENDING",null,now,expires);
    }
    private void insert(UUID notification,UUID user,String channel,UUID subscription,String status,String reason,Instant now,Instant expires){
        db.update("""
            INSERT INTO notification_deliveries(id,notification_id,recipient_user_id,channel,subscription_id,status,skip_reason,next_attempt_at,expires_at,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",UUID.randomUUID(),notification,user,channel,subscription,status,reason,
            Timestamp.from(now),Timestamp.from(expires),Timestamp.from(now),Timestamp.from(now));
    }
}
