package com.datn.notification.delivery;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Push subscriptions keyed by endpoint. Registering an endpoint already known (same browser after another login)
 * moves it to the current user; at most MAX_ACTIVE per user, oldest revoked first. Endpoint/keys are never logged.
 */
@Service @RequiredArgsConstructor @Transactional
public class PushSubscriptions {
    public static final int MAX_ACTIVE=10;
    private final JdbcTemplate db;private final PushEndpointPolicy policy;private final WebPushSender push;private final Clock clock;

    public record PublicKey(boolean enabled,String publicKey){}
    public record Keys(String p256dh,String auth){}
    /** Shape of the browser's PushSubscription.toJSON(); expirationTime is accepted and ignored. */
    public record SubscribeRequest(String endpoint,Keys keys,Long expirationTime){}
    public record Registered(UUID id,Instant createdAt){}

    @Transactional(readOnly=true)
    public PublicKey publicKey(){return new PublicKey(push.configured(),push.publicKey());}

    public Registered subscribe(UUID user,SubscribeRequest req){
        if(req==null || req.keys()==null || req.keys().p256dh()==null || req.keys().auth()==null || req.keys().p256dh().length()>128 || req.keys().auth().length()>64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid push subscription");
        try{policy.validate(req.endpoint());WebPushSender.validateKeys(req.keys().p256dh(),req.keys().auth());}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        Instant now=clock.instant();
        var row=db.queryForMap("""
            INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth_secret,created_at,updated_at) VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(endpoint) DO UPDATE SET user_id=EXCLUDED.user_id,p256dh=EXCLUDED.p256dh,auth_secret=EXCLUDED.auth_secret,updated_at=EXCLUDED.updated_at,revoked_at=NULL
            RETURNING id,created_at""",UUID.randomUUID(),user,req.endpoint(),req.keys().p256dh(),req.keys().auth(),ts(now),ts(now));
        db.update("""
            UPDATE push_subscriptions SET revoked_at=?,updated_at=? WHERE id IN (
              SELECT id FROM push_subscriptions WHERE user_id=? AND revoked_at IS NULL ORDER BY updated_at DESC,id OFFSET ?)""",ts(now),ts(now),user,MAX_ACTIVE);
        return new Registered((UUID)row.get("id"),((Timestamp)row.get("created_at")).toInstant());
    }
    /** Idempotent for the owner; another user's or an unknown ID is 404 and changes nothing. */
    public void unsubscribe(UUID user,UUID id){
        Instant now=clock.instant();
        int owned=db.update("UPDATE push_subscriptions SET revoked_at=COALESCE(revoked_at,?),updated_at=? WHERE id=? AND user_id=?",ts(now),ts(now),id,user);
        if(owned==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Subscription not found");
        db.update("UPDATE notification_deliveries SET status='SKIPPED',skip_reason='UNSUBSCRIBED',updated_at=? WHERE subscription_id=? AND status='PENDING'",ts(now),id);
    }
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
}
