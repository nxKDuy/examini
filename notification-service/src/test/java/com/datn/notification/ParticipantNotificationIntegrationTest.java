package com.datn.notification;
import com.datn.notification.config.RabbitMQConfig;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Participant and attempt events through the real RabbitMQ binding into the PostgreSQL inbox. */
class ParticipantNotificationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;@Autowired RabbitTemplate rabbit;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    UUID exam=UUID.randomUUID(),student=UUID.randomUUID(),other=UUID.randomUUID(),teacher=UUID.randomUUID(),session=UUID.randomUUID();

    String bearer(UUID user){
        return "Bearer "+Jwts.builder().subject(user.toString()).claim("type","access").claim("role","STUDENT").expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    Map<String,Object> changed(String cause,Object quantity,String reason){
        var p=new LinkedHashMap<String,Object>();
        p.put("examId",exam.toString());p.put("examTitle","Toán 12");p.put("userId",student.toString());p.put("cause",cause);p.put("actorUserId",teacher.toString());
        p.put("quantity",quantity);p.put("publicReason",reason);p.put("recipientRevision",2);p.put("registeredCount",5);p.put("eligibleCount",4);p.put("revokedCount",1);return p;
    }
    Map<String,Object> invalidated(){
        var p=new LinkedHashMap<String,Object>();
        p.put("sessionId",session.toString());p.put("examId",exam.toString());p.put("userId",student.toString());p.put("attemptNumber",1);p.put("status","SUBMITTED");
        p.put("reason","Vi phạm quy chế");p.put("actor",teacher.toString());p.put("invalidatedAt","2026-10-08T03:00:00Z");return p;
    }
    void publish(UUID eventId,String type,Map<String,Object> payload)throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,type,new Message(json.writeValueAsBytes(Map.of("eventId",eventId.toString(),"eventType",type,"eventVersion",1,
            "occurredAt","2026-10-08T03:00:00Z","payload",payload)),props));
    }
    long count(UUID eventId){return db.queryForObject("SELECT count(*) FROM notifications WHERE source_event_id=?",Long.class,eventId);}
    void await(java.util.function.BooleanSupplier done)throws InterruptedException{
        for(int i=0;i<100 && !done.getAsBoolean();i++)Thread.sleep(100);assertTrue(done.getAsBoolean(),"timed out waiting for broker delivery");
    }

    @Test void changedAndInvalidatedReachOnlyTheStudentOnceDespiteRedelivery()throws Exception{
        UUID granted=UUID.randomUUID(),revoked=UUID.randomUUID(),joined=UUID.randomUUID(),inv=UUID.randomUUID();
        var grant=changed("ATTEMPT_GRANTED",2,"Sự cố mạng");
        publish(granted,"exam.participant.changed",grant);publish(granted,"exam.participant.changed",grant);publish(granted,"exam.participant.changed",grant);
        publish(revoked,"exam.participant.changed",changed("REVOKED",null,null));
        publish(joined,"exam.participant.changed",changed("JOINED",null,null));
        publish(inv,"exam.attempt.invalidated",invalidated());publish(inv,"exam.attempt.invalidated",invalidated());
        await(()->count(granted)>=1 && count(revoked)>=1 && count(inv)>=1);Thread.sleep(1000);
        assertEquals(1,count(granted),"three deliveries: one notice");assertEquals(1,count(revoked));assertEquals(1,count(inv));
        assertEquals(0,count(joined),"JOINED is consumed silently");
        assertEquals(3L,db.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id=?",Long.class,student));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM notification_deliveries d JOIN notifications n ON n.id=d.notification_id WHERE n.recipient_user_id=?",Long.class,student),"no new channels");
        var rows=db.queryForList("SELECT type,entity_type,metadata->>'link' AS link FROM notifications WHERE recipient_user_id=? ORDER BY type",student);
        assertEquals(List.of("ATTEMPT_GRANTED","ATTEMPT_INVALIDATED","PARTICIPANT_REVOKED"),rows.stream().map(r->r.get("type").toString()).toList());
        assertEquals(Map.of("ATTEMPT_GRANTED","/exam/lobby/"+exam,"ATTEMPT_INVALIDATED","/exam/result/"+session,"PARTICIPANT_REVOKED","/my-exams"),
            rows.stream().collect(java.util.stream.Collectors.toMap(r->r.get("type").toString(),r->r.get("link").toString())));
        String body=mvc.perform(get("/notifications").header("Authorization",bearer(student))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(teacher.toString()),"no actor ID in the inbox");
        for(UUID stranger:List.of(other,teacher))mvc.perform(get("/notifications").header("Authorization",bearer(stranger))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void malformedParticipantAndAttemptEventsAreDeadLettered()throws Exception{
        while(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)!=null){}
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        publish(a,"exam.participant.changed",changed("ATTEMPT_GRANTED",null,null));   // quantity required
        var badAttempt=invalidated();badAttempt.put("attemptNumber",1.5);publish(b,"exam.attempt.invalidated",badAttempt);
        publish(c,"exam.participant.changed",changed("PROMOTED",null,null));
        int dead=0;for(int i=0;i<3;i++)if(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE,10000)!=null)dead++;
        assertEquals(3,dead);
        for(UUID id:List.of(a,b,c))assertEquals(0,count(id));
        mvc.perform(get("/notifications").header("Authorization",bearer(student))).andExpect(jsonPath("$.totalElements").value(0));
    }
}
