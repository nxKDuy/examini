package com.datn.notification.delivery;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

/**
 * Per-user email/push switches by category. Only categories that ever leave the inbox are listed; anything else
 * (organization, participant notices) is inbox-only and needs no switch. Default is on: no row = both channels on.
 * Checked when a delivery is queued and again right before each send, so turning a channel off also stops queued retries.
 */
@Component
public class Preferences {
    public enum Category { SCHEDULE, REMINDER, EXAM_STATUS, RESULT, GRADING }
    public record Setting(Category category,boolean email,boolean push){}
    public static final String SKIP_REASON="USER_PREFERENCE";

    private final JdbcTemplate db;private final Clock clock;
    public Preferences(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}

    /** Category of a notification type, or null when it has no channel delivery to switch. */
    public static Category category(String type){
        if(type==null)return null;
        return switch(type){
            case "EXAM_SCHEDULED","EXAM_RESCHEDULED","EXAM_UNSCHEDULED" -> Category.SCHEDULE;
            case "EXAM_REMINDER" -> Category.REMINDER;
            case "EXAM_OPENED","EXAM_CLOSED" -> Category.EXAM_STATUS;
            case "RESULT_AVAILABLE","RESULT_REGRADED" -> Category.RESULT;
            case "GRADING_REVIEW_REQUIRED" -> Category.GRADING;
            default -> null;
        };
    }

    public List<Setting> list(UUID user){
        var stored=new EnumMap<Category,Setting>(Category.class);
        db.query("SELECT category,email,push FROM notification_preferences WHERE user_id=?",rs->{
            var c=Category.valueOf(rs.getString(1));stored.put(c,new Setting(c,rs.getBoolean(2),rs.getBoolean(3)));
        },user);
        var out=new ArrayList<Setting>();
        for(Category c:Category.values())out.add(stored.getOrDefault(c,new Setting(c,true,true)));
        return out;
    }

    /** Upserts the given categories; categories left out keep their value. Returns the full list. */
    @Transactional
    public List<Setting> update(UUID user,List<Setting> changes){
        if(changes==null||changes.isEmpty()||changes.size()>Category.values().length)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Danh sách cài đặt không hợp lệ");
        var seen=EnumSet.noneOf(Category.class);
        for(Setting s:changes){
            if(s==null||s.category()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Thiếu loại thông báo");
            if(!seen.add(s.category()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Loại thông báo bị lặp: "+s.category());
        }
        Timestamp now=Timestamp.from(clock.instant());
        for(Setting s:changes)db.update("""
            INSERT INTO notification_preferences(user_id,category,email,push,updated_at) VALUES(?,?,?,?,?)
            ON CONFLICT(user_id,category) DO UPDATE SET email=EXCLUDED.email,push=EXCLUDED.push,updated_at=EXCLUDED.updated_at""",
            user,s.category().name(),s.email(),s.push(),now);
        return list(user);
    }

    /** Whether this user still wants this notification type on this channel ("EMAIL" or "PUSH"). */
    public boolean allows(UUID user,String type,String channel){
        Category c=category(type);
        if(c==null)return true;
        List<Boolean> v=db.queryForList("SELECT "+("EMAIL".equals(channel)?"email":"push")+" FROM notification_preferences WHERE user_id=? AND category=?",Boolean.class,user,c.name());
        return v.isEmpty()||Boolean.TRUE.equals(v.getFirst());
    }

    /** Batch form for the delivery worker: (user, category, channel) triples that are switched off. */
    public Set<String> disabled(Collection<UUID> users){
        var off=new HashSet<String>();
        if(users.isEmpty())return off;
        String in=String.join(",",Collections.nCopies(users.size(),"?"));
        db.query("SELECT user_id,category,email,push FROM notification_preferences WHERE user_id IN ("+in+") AND (NOT email OR NOT push)",rs->{
            String k=rs.getString(1)+"|"+rs.getString(2)+"|";
            if(!rs.getBoolean(3))off.add(k+"EMAIL");
            if(!rs.getBoolean(4))off.add(k+"PUSH");
        },users.toArray());
        return off;
    }
    public static String key(UUID user,String type,String channel){
        Category c=category(type);return c==null?null:user+"|"+c.name()+"|"+channel;
    }
}
