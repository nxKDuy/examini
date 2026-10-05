package com.datn.notification;
import com.datn.notification.config.RabbitMQConfig;
import com.datn.notification.repository.NotificationRepository;
import com.datn.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

/** Runs against real PostgreSQL and RabbitMQ (TEST_DB_URL / TEST_RABBIT_HOST). */
@SpringBootTest(properties={"spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/notification_test}","spring.datasource.username=${TEST_DB_USER:postgres}","spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.rabbitmq.host=${TEST_RABBIT_HOST:localhost}","spring.rabbitmq.port=${TEST_RABBIT_PORT:5672}","spring.rabbitmq.username=guest","spring.rabbitmq.password=guest",
    "spring.rabbitmq.listener.simple.retry.initial-interval=100ms","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret="+NotificationIntegrationTest.KEY})
@AutoConfigureMockMvc
class NotificationIntegrationTest {
    static final String KEY="test-only-jwt-key-32-bytes-or-longer-never-production";
    @Autowired MockMvc mvc;@Autowired RabbitTemplate rabbit;@Autowired NotificationService service;@Autowired NotificationRepository repo;@Autowired ObjectMapper json;
    UUID alice=UUID.randomUUID(),bob=UUID.randomUUID();

    String token(UUID user,String type,String role){
        return Jwts.builder().subject(user.toString()).claim("type",type).claim("role",role).expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    String bearer(UUID user){return "Bearer "+token(user,"access","STUDENT");}
    Map<String,Object> envelope(UUID eventId,String type,Map<String,Object> payload){
        return Map.of("eventId",eventId.toString(),"eventType",type,"eventVersion",1,"occurredAt","2026-10-05T10:00:00Z","payload",payload);
    }
    Map<String,Object> assigned(UUID user,String title){return Map.of("examId",UUID.randomUUID().toString(),"examTitle",title,"userId",user.toString());}
    void publish(Object event,String routingKey)throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,routingKey,new Message(json.writeValueAsBytes(event),props));
    }
    void deliverDirect(Map<String,Object> event)throws Exception{service.onEvent(json.writeValueAsBytes(event));}
    long count(UUID eventId){return repo.findAll().stream().filter(n->n.getSourceEventId().equals(eventId)).count();}
    void await(java.util.function.BooleanSupplier done)throws InterruptedException{
        for(int i=0;i<100 && !done.getAsBoolean();i++)Thread.sleep(100);assertTrue(done.getAsBoolean(),"timed out waiting for broker delivery");
    }

    @Test void validEventsThroughRabbitCreateRecipientNotifications()throws Exception{
        UUID reviewed=UUID.randomUUID(),graded=UUID.randomUUID(),question=UUID.randomUUID(),session=UUID.randomUUID();
        publish(envelope(reviewed,"question.reviewed",Map.of("questionId",question.toString(),"questionVersion",3,"ownerUserId",alice.toString(),"decision","APPROVED",
            "correctAnswer","SECRET-KEY","reviewNote","private")),"question.reviewed");
        publish(envelope(graded,"grading.result.released",Map.of("sessionId",session.toString(),"examId",UUID.randomUUID().toString(),"examTitle","Toán 12",
            "studentUserId",alice.toString(),"gradingMode","MANUAL","score",9.5)),"grading.result.released");
        await(()->count(reviewed)==1 && count(graded)==1);
        mvc.perform(get("/notifications").header("Authorization",bearer(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].type").value("RESULT_AVAILABLE"))
            .andExpect(jsonPath("$.content[0].entityType").value("EXAM_SESSION"))
            .andExpect(jsonPath("$.content[0].entityId").value(session.toString()))
            .andExpect(jsonPath("$.content[0].read").value(false))
            .andExpect(jsonPath("$.content[1].type").value("QUESTION_APPROVED"))
            .andExpect(jsonPath("$.content[1].entityId").value(question.toString()))
            .andExpect(jsonPath("$.content[1].metadata.questionVersion").value("3"))
            // Only whitelisted display fields are kept from event payloads.
            .andExpect(content().string(not(containsString("SECRET-KEY"))))
            .andExpect(content().string(not(containsString("private"))))
            .andExpect(content().string(not(containsString("9.5"))));
    }

    @Test void duplicateDeliveryCreatesOneNotification()throws Exception{
        UUID eventId=UUID.randomUUID();var event=envelope(eventId,"exam.participant.assigned",assigned(alice,"Giữa kỳ"));
        publish(event,"exam.participant.assigned");publish(event,"exam.participant.assigned");
        var pool=Executors.newFixedThreadPool(4);
        try{var calls=new ArrayList<Future<?>>();for(int i=0;i<4;i++)calls.add(pool.submit(()->{deliverDirect(event);return null;}));for(var c:calls)c.get();}finally{pool.shutdownNow();}
        await(()->count(eventId)>=1);Thread.sleep(500);
        assertEquals(1,count(eventId));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(alice))).andExpect(jsonPath("$.count").value(1));
    }

    @Test void malformedAndUnknownVersionEventsAreDeadLetteredNotGuessed()throws Exception{
        while(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)!=null){}
        UUID badVersion=UUID.randomUUID();
        publish(Map.of("eventId",badVersion.toString(),"eventType","exam.participant.assigned","eventVersion",2,"payload",assigned(alice,"X")),"exam.participant.assigned");
        publish(envelope(UUID.randomUUID(),"exam.participant.assigned",Map.of("examTitle","no recipient")),"exam.participant.assigned");
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,"session.submitted",new Message("not json".getBytes(StandardCharsets.UTF_8),new MessageProperties()));
        int dead=0;for(int i=0;i<3;i++)if(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE,10000)!=null)dead++;
        assertEquals(3,dead);assertEquals(0,count(badVersion));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(alice))).andExpect(jsonPath("$.count").value(0));
    }

    @Test void listIsOwnNewestFirstPaginatedAndFilterable()throws Exception{
        for(int i=1;i<=5;i++)deliverDirect(envelope(UUID.randomUUID(),"exam.participant.assigned",assigned(alice,"Exam "+i)));
        deliverDirect(envelope(UUID.randomUUID(),"exam.participant.assigned",assigned(bob,"Bob only")));
        mvc.perform(get("/notifications?page=0&size=2").header("Authorization",bearer(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].metadata.examTitle").value("Exam 5"))
            .andExpect(jsonPath("$.content[1].metadata.examTitle").value("Exam 4"));
        mvc.perform(get("/notifications?page=2&size=2&sort=createdAt,asc").header("Authorization",bearer(alice)))
            .andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.content[0].metadata.examTitle").value("Exam 1"));
        mvc.perform(get("/notifications?size=500").header("Authorization",bearer(alice))).andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/notifications").header("Authorization",bearer(alice))).andExpect(content().string(not(containsString("Bob only"))));
        String first=json.readTree(mvc.perform(get("/notifications?size=1").header("Authorization",bearer(alice))).andReturn().getResponse().getContentAsString()).at("/content/0/id").asText();
        mvc.perform(patch("/notifications/"+first+"/read").header("Authorization",bearer(alice))).andExpect(status().isOk());
        mvc.perform(get("/notifications?read=true").header("Authorization",bearer(alice))).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(first));
        mvc.perform(get("/notifications?read=false").header("Authorization",bearer(alice))).andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test void markReadAndReadAllAreIdempotentAndCounted()throws Exception{
        for(int i=0;i<3;i++)deliverDirect(envelope(UUID.randomUUID(),"exam.member.invited",Map.of("examId",UUID.randomUUID().toString(),"examTitle","Cuối kỳ","userId",alice.toString(),"role","PROCTOR")));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(alice))).andExpect(jsonPath("$.count").value(3));
        String id=json.readTree(mvc.perform(get("/notifications?size=1").header("Authorization",bearer(alice))).andReturn().getResponse().getContentAsString()).at("/content/0/id").asText();
        String readAt=json.readTree(mvc.perform(patch("/notifications/"+id+"/read").header("Authorization",bearer(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.read").value(true)).andExpect(jsonPath("$.message").value(containsString("Giám thị"))).andReturn().getResponse().getContentAsString()).get("readAt").asText();
        mvc.perform(patch("/notifications/"+id+"/read").header("Authorization",bearer(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.readAt").value(readAt));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(alice))).andExpect(jsonPath("$.count").value(2));
        mvc.perform(patch("/notifications/read-all").header("Authorization",bearer(alice))).andExpect(jsonPath("$.updated").value(2));
        mvc.perform(patch("/notifications/read-all").header("Authorization",bearer(alice))).andExpect(jsonPath("$.updated").value(0));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(alice))).andExpect(jsonPath("$.count").value(0));
    }

    @Test void userCannotReadOrMutateAnotherUsersNotification()throws Exception{
        deliverDirect(envelope(UUID.randomUUID(),"exam.participant.assigned",assigned(alice,"Alice private")));
        deliverDirect(envelope(UUID.randomUUID(),"exam.participant.assigned",assigned(bob,"Bob own")));
        String aliceId=json.readTree(mvc.perform(get("/notifications").header("Authorization",bearer(alice))).andReturn().getResponse().getContentAsString()).at("/content/0/id").asText();
        // Bob (even as ADMIN, even spoofing Alice's identity headers) gets 404 and changes nothing.
        String bobAdmin="Bearer "+token(bob,"access","ADMIN");
        mvc.perform(patch("/notifications/"+aliceId+"/read").header("Authorization",bobAdmin).header("X-User-Id",alice.toString())).andExpect(status().isNotFound());
        mvc.perform(patch("/notifications/read-all").header("Authorization",bobAdmin).header("X-User-Id",alice.toString())).andExpect(jsonPath("$.updated").value(1));
        mvc.perform(get("/notifications?userId="+alice).header("Authorization",bobAdmin).header("X-User-Id",alice.toString()))
            .andExpect(jsonPath("$.totalElements").value(1)).andExpect(content().string(not(containsString("Alice private"))));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(alice))).andExpect(jsonPath("$.count").value(1));
        assertNull(repo.findById(UUID.fromString(aliceId)).orElseThrow().getReadAt());
        mvc.perform(patch("/notifications/"+UUID.randomUUID()+"/read").header("Authorization",bearer(alice))).andExpect(status().isNotFound());
    }

    @Test void onlyValidAccessTokensAuthenticate()throws Exception{
        mvc.perform(get("/notifications").header("X-User-Id",alice.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications").header("Authorization","Bearer "+token(alice,"refresh","STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications").header("Authorization","Bearer "+token(alice,"access","SUPERUSER"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/notifications").header("Authorization",bearer(alice)).header("X-Service-Token","anything")).andExpect(status().isUnauthorized());
        String forged=Jwts.builder().subject(alice.toString()).claim("type","access").claim("role","STUDENT").expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(Keys.hmacShaKeyFor("another-key-that-is-32-bytes-or-longer-for-test".getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/notifications").header("Authorization","Bearer "+forged)).andExpect(status().isUnauthorized());
    }
}
