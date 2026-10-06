package com.datn.notification.delivery;
import com.datn.notification.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Sends EMAIL and PUSH deliveries. Each row (email, or one push subscription) is claimed with FOR UPDATE SKIP LOCKED
 * and a lease, so a partially successful multi-device push only retries the failed endpoints.
 * Guarantee: at-least-once per row with bounded retries. If the provider accepted a message but the result was lost
 * (crash/timeout after acceptance), the row is retried and a duplicate physical email/push is possible; SENT is only
 * recorded after an explicit provider acceptance (SMTP success, push 201/202).
 */
@Service
public class DeliveryWorker {
    static final Duration LEASE=Duration.ofMinutes(2),MAX_BACKOFF=Duration.ofMinutes(30);
    static final int MAX_ATTEMPTS=8,PUSH_BODY_LIMIT=300;
    private final JdbcTemplate db;private final Clock clock;private final ObjectMapper json;
    private final AuthContactClient contacts;private final EmailSender email;private final WebPushSender push;
    private final String appBaseUrl;private final int batch;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DeliveryWorker.class);
    public DeliveryWorker(JdbcTemplate db,Clock clock,ObjectMapper json,AuthContactClient contacts,EmailSender email,WebPushSender push,
                          @Value("${notification.app-base-url}") String appBaseUrl,@Value("${notification.deliveries.batch-size:50}") int batch){
        this.db=db;this.clock=clock;this.json=json;this.contacts=contacts;this.email=email;this.push=push;this.appBaseUrl=appBaseUrl.replaceAll("/+$","");
        // One Auth contact lookup per batch, bounded by its 100-ID limit.
        this.batch=Math.min(batch,AuthContactClient.MAX_IDS);
    }

    record Row(UUID id,String channel,UUID userId,UUID subscriptionId,int attempts,Instant expiresAt,UUID notificationId,String type,String title,String message,String link){}

    public int runDue(){
        Instant now=clock.instant();
        db.update("""
            UPDATE notification_deliveries SET status='EXPIRED',locked_until=NULL,updated_at=?
            WHERE (status='PENDING' OR (status='SENDING' AND locked_until<?)) AND expires_at<=?""",ts(now),ts(now),ts(now));
        List<Row> rows=db.query("""
            WITH claimed AS (
              UPDATE notification_deliveries SET status='SENDING',locked_until=?,attempts=attempts+1,updated_at=?
              WHERE id IN (SELECT id FROM notification_deliveries WHERE ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND locked_until<?))
                           AND expires_at>? ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
              RETURNING id,channel,recipient_user_id,subscription_id,attempts,expires_at,notification_id)
            SELECT c.id,c.channel,c.recipient_user_id,c.subscription_id,c.attempts,c.expires_at,n.id,n.type,n.title,n.message,n.metadata->>'link'
            FROM claimed c JOIN notifications n ON n.id=c.notification_id""",
            (r,i)->new Row(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getInt(5),r.getTimestamp(6).toInstant(),
                r.getObject(7,UUID.class),r.getString(8),r.getString(9),r.getString(10),r.getString(11)),
            ts(now.plus(LEASE)),ts(now),ts(now),ts(now),ts(now),batch);
        var emails=rows.stream().filter(r->r.channel().equals("EMAIL")).toList();
        if(!emails.isEmpty())sendEmails(emails);
        rows.stream().filter(r->r.channel().equals("PUSH")).forEach(this::sendPush);
        return rows.size();
    }

    private void sendEmails(List<Row> rows){
        if(!email.configured()){rows.forEach(r->retry(r,"email not configured",null,null));return;}
        Map<UUID,AuthContactClient.Contact> found;
        try{found=contacts.contacts(rows.stream().map(Row::userId).toList());}
        catch(ProviderException e){rows.forEach(r->retry(r,e.getMessage(),null,null));return;}
        for(Row r:rows){
            var c=found.get(r.userId());
            if(c==null){finish(r,"SKIPPED","NO_CONTACT",null,null);continue;}
            try{
                email.send(c.email(),"[EXAMINI] "+r.title(),r.message()+"\n\nXem chi tiết: "+url(r)+"\n\nEmail tự động từ EXAMINI, vui lòng không trả lời.");
                finish(r,"SENT",null,null,null);
            }catch(Exception e){
                log.warn("Email delivery {} to {} failed: {}",r.id(),AuthContactClient.mask(c.email()),e.getClass().getSimpleName());
                retry(r,"smtp: "+e.getClass().getSimpleName(),null,null);
            }
        }
    }

    private void sendPush(Row r){
        if(!push.configured()){retry(r,"push not configured",null,null);return;}
        // Owner re-checked at send time: an endpoint re-registered by another account never receives this user's push.
        var sub=db.query("SELECT endpoint,p256dh,auth_secret,revoked_at FROM push_subscriptions WHERE id=? AND user_id=?",
            (x,i)->new String[]{x.getString(1),x.getString(2),x.getString(3),x.getTimestamp(4)==null?null:"revoked"},r.subscriptionId(),r.userId());
        if(sub.isEmpty() || sub.getFirst()[3]!=null){finish(r,"SKIPPED","UNSUBSCRIBED",null,null);return;}
        String body=r.message().length()>PUSH_BODY_LIMIT?r.message().substring(0,PUSH_BODY_LIMIT-1)+"…":r.message();
        byte[] payload;
        try{payload=json.writeValueAsBytes(Map.of("notificationId",r.notificationId().toString(),"type",r.type(),"title",r.title(),"body",body,"url",url(r),"tag",r.notificationId().toString()));}
        catch(Exception e){throw new IllegalStateException(e);}
        Duration ttl=Duration.between(clock.instant(),r.expiresAt());
        WebPushSender.Result res;
        try{res=push.send(sub.getFirst()[0],sub.getFirst()[1],sub.getFirst()[2],payload,ttl,r.type().equals("EXAM_REMINDER"),topic(r.notificationId()));}
        catch(IllegalArgumentException e){finish(r,"FAILED","INVALID_SUBSCRIPTION",null,e.getMessage());return;}
        int s=res.status();
        if(s==201 || s==202)finish(r,"SENT",null,s,null);
        else if(s==404 || s==410){
            db.update("UPDATE push_subscriptions SET revoked_at=?,updated_at=? WHERE id=? AND revoked_at IS NULL",ts(clock.instant()),ts(clock.instant()),r.subscriptionId());
            finish(r,"FAILED","SUBSCRIPTION_GONE",s,"push service: subscription gone");
        }
        else if(s==-1 || s==429 || s>=500)retry(r,s==-1?"push service unreachable":"push service "+s,res.retryAfter(),s==-1?null:s);
        else finish(r,"FAILED","REJECTED",s,"push service rejected "+s);
    }

    /** Absolute SPA URL for email/push; the in-app inbox keeps the relative path. */
    String url(Row r){return appBaseUrl+(r.link()==null?"/dashboard":r.link());}
    /** RFC 8030 Topic (<=32 base64url chars): an undelivered duplicate replaces the queued one at the push service. */
    static String topic(UUID id){return WebPushSender.b64(ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());}

    private void finish(Row r,String status,String reason,Integer provider,String error){
        Instant now=clock.instant();
        db.update("""
            UPDATE notification_deliveries SET status=?,skip_reason=?,provider_status=?,last_error=?,locked_until=NULL,updated_at=?,sent_at=CASE WHEN ?='SENT' THEN ? ELSE sent_at END
            WHERE id=? AND status='SENDING'""",status,reason,provider,trim(error),ts(now),status,ts(now),r.id());
    }
    private void retry(Row r,String error,Duration after,Integer provider){
        if(r.attempts()>=MAX_ATTEMPTS){finish(r,"FAILED","RETRIES_EXHAUSTED",provider,error);return;}
        Duration wait=after!=null?after:backoff(r.attempts());
        Instant now=clock.instant();
        db.update("UPDATE notification_deliveries SET status='PENDING',provider_status=?,last_error=?,next_attempt_at=?,locked_until=NULL,updated_at=? WHERE id=? AND status='SENDING'",
            provider,trim(error),ts(now.plus(wait)),ts(now),r.id());
    }
    static Duration backoff(int attempts){
        Duration d=Duration.ofSeconds(30).multipliedBy(1L<<Math.min(Math.max(attempts-1,0),10));
        return d.compareTo(MAX_BACKOFF)>0?MAX_BACKOFF:d;
    }
    private static String trim(String s){return s==null?null:s.substring(0,Math.min(300,s.length()));}
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
}
