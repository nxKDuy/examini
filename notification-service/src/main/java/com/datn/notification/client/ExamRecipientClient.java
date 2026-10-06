package com.datn.notification.client;
import com.datn.notification.service.SchedulingEvents;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.*;

/** GET /exams/{id}/internal/notification-recipients with EXAM_NOTIFICATION_TOKEN (Exam is the recipient authority). */
@Component
public class ExamRecipientClient {
    public static final List<String> RELATIONS=List.of("OWNER","MANAGER","ASSIGNED","JOINED","FOLLOWER");
    private final ObjectMapper json;private final String baseUrl,token;
    public ExamRecipientClient(ObjectMapper json,@Value("${notification.exam.base-url}") String baseUrl,@Value("${notification.exam.token:}") String token){this.json=json;this.baseUrl=baseUrl;this.token=token;}

    public record Recipient(UUID userId,String relation,boolean started,boolean submitted){
        public boolean manager(){return relation.equals("OWNER") || relation.equals("MANAGER");}
    }
    public record Snapshot(UUID examId,String examTitle,String status,long scheduleRevision,long lifecycleRevision,long recipientRevision,Instant startAt,Instant endAt,
                           String timezone,String deadlineRule,List<Recipient> recipients){}

    public Snapshot recipients(UUID examId){
        JsonNode r=ServiceHttp.call(json,"Exam",token,HttpRequest.newBuilder(ServiceHttp.uri(baseUrl,"/exams/"+examId+"/internal/notification-recipients")).GET());
        try{
            if(!r.isObject() || !r.path("recipients").isArray())throw new IllegalArgumentException("recipients array required");
            // Provider deduplicates; keep the strongest relation per user defensively.
            var byUser=new LinkedHashMap<UUID,Recipient>();
            for(JsonNode n:r.get("recipients")){
                var rec=new Recipient(UUID.fromString(n.path("userId").asText()),relation(n.path("relation").asText()),bool(n,"started"),bool(n,"submitted"));
                byUser.merge(rec.userId(),rec,(a,b)->RELATIONS.indexOf(a.relation())<=RELATIONS.indexOf(b.relation())?a:b);
            }
            String status=r.path("status").asText();
            if(!List.of("DRAFT","PUBLISHED","OPEN","CLOSED").contains(status))throw new IllegalArgumentException("status");
            return new Snapshot(UUID.fromString(r.path("examId").asText()),r.path("examTitle").asText(),status,count(r,"scheduleRevision"),count(r,"lifecycleRevision"),count(r,"recipientRevision"),
                SchedulingEvents.optionalInstant(r,"startAt"),SchedulingEvents.optionalInstant(r,"endAt"),r.path("timezone").asText("UTC"),
                r.path("deadlineRule").asText("FULL_DURATION"),List.copyOf(byUser.values()));
        }catch(IllegalArgumentException e){throw new ProviderException(ProviderException.Kind.INVALID,"Exam recipients response invalid");}
    }
    private static String relation(String r){if(!RELATIONS.contains(r))throw new IllegalArgumentException("relation");return r;}
    private static boolean bool(JsonNode n,String f){if(!n.path(f).isBoolean())throw new IllegalArgumentException(f);return n.get(f).booleanValue();}
    private static long count(JsonNode n,String f){if(!n.path(f).isIntegralNumber() || n.get(f).longValue()<0)throw new IllegalArgumentException(f);return n.get(f).longValue();}
}
