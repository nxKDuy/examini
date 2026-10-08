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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Organization events through the real RabbitMQ binding into the PostgreSQL inbox; only the recipient can read them. */
class OrganizationNotificationIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;@Autowired RabbitTemplate rabbit;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    UUID invitee=UUID.randomUUID(),inviter=UUID.randomUUID(),stranger=UUID.randomUUID(),org=UUID.randomUUID(),invitation=UUID.randomUUID(),membership=UUID.randomUUID();

    String bearer(UUID user){
        return "Bearer "+Jwts.builder().subject(user.toString()).claim("type","access").claim("role","TEACHER").expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8))).compact();
    }
    Map<String,Object> payload(UUID recipient,Map<String,Object> extra){
        var p=new LinkedHashMap<String,Object>();p.put("recipientUserId",recipient.toString());p.put("organizationId",org.toString());p.put("organizationName","Trường A");p.putAll(extra);return p;
    }
    void publish(UUID eventId,String type,Map<String,Object> payload)throws Exception{publishRaw(Map.of("eventId",eventId.toString(),"eventType",type,"eventVersion",1,"occurredAt","2026-10-08T01:00:00Z","payload",payload),type);}
    void publishRaw(Object envelope,String routingKey)throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RabbitMQConfig.EVENTS_EXCHANGE,routingKey,new Message(json.writeValueAsBytes(envelope),props));
    }
    long count(UUID eventId){return db.queryForObject("SELECT count(*) FROM notifications WHERE source_event_id=?",Long.class,eventId);}
    void await(java.util.function.BooleanSupplier done)throws InterruptedException{
        for(int i=0;i<100 && !done.getAsBoolean();i++)Thread.sleep(100);assertTrue(done.getAsBoolean(),"timed out waiting for broker delivery");
    }

    @Test void invitationCreatedReachesOnlyTheInviteeAndRedeliveryCreatesOne()throws Exception{
        UUID event=UUID.randomUUID();
        var p=payload(invitee,Map.of("invitationId",invitation.toString(),"role","MEMBER","invitedByUserId",inviter.toString(),"expiresAt","2026-10-15T08:30:00Z",
            "link","/organizations/invitations","email","secret@example.test"));
        publish(event,"organization.invitation.created",p);publish(event,"organization.invitation.created",p);publish(event,"organization.invitation.created",p);
        await(()->count(event)>=1);Thread.sleep(700);
        assertEquals(1,count(event),"three deliveries of one event: one logical notification");
        String body=mvc.perform(get("/notifications").header("Authorization",bearer(invitee))).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].type").value("ORGANIZATION_INVITATION_CREATED"))
            .andExpect(jsonPath("$.content[0].entityType").value("ORGANIZATION_INVITATION")).andExpect(jsonPath("$.content[0].entityId").value(invitation.toString()))
            .andExpect(jsonPath("$.content[0].metadata.link").value("/organizations/invitations")).andExpect(jsonPath("$.content[0].metadata.organizationId").value(org.toString()))
            .andExpect(jsonPath("$.content[0].metadata.role").value("MEMBER")).andExpect(jsonPath("$.content[0].read").value(false))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("secret@example.test") || body.contains(inviter.toString()),"no email or other user's ID in the inbox");
        for(UUID other:List.of(inviter,stranger))
            mvc.perform(get("/notifications").header("Authorization",bearer(other))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/notifications/unread-count").header("Authorization",bearer(invitee))).andExpect(jsonPath("$.count").value(1));
    }

    @Test void allEightEventsLandInTheRecipientsInboxWithTheAllowlistedRoute()throws Exception{
        var expectations=new LinkedHashMap<String,String[]>();   // type -> {noticeType, route}
        var recipients=new LinkedHashMap<String,UUID>();
        var events=new LinkedHashMap<String,UUID>();
        Map<String,Map<String,Object>> extras=new LinkedHashMap<>();
        extras.put("organization.invitation.created",Map.of("invitationId",invitation.toString(),"role","ADMIN","invitedByUserId",inviter.toString(),"expiresAt","2026-10-15T08:30:00Z"));
        extras.put("organization.invitation.accepted",Map.of("invitationId",invitation.toString(),"memberUserId",invitee.toString()));
        extras.put("organization.invitation.declined",Map.of("invitationId",invitation.toString(),"declinedByUserId",invitee.toString()));
        extras.put("organization.invitation.revoked",Map.of("invitationId",invitation.toString()));
        extras.put("organization.invitation.expired",Map.of("invitationId",invitation.toString()));
        extras.put("organization.membership.revoked",Map.of("membershipId",membership.toString()));
        extras.put("organization.membership.role-changed",Map.of("membershipId",membership.toString(),"role","MEMBER"));
        extras.put("organization.ownership.transferred",Map.of("membershipId",membership.toString()));
        expectations.put("organization.invitation.created",new String[]{"ORGANIZATION_INVITATION_CREATED","/organizations/invitations"});
        expectations.put("organization.invitation.accepted",new String[]{"ORGANIZATION_INVITATION_ACCEPTED","/organizations/"+org});
        expectations.put("organization.invitation.declined",new String[]{"ORGANIZATION_INVITATION_DECLINED","/organizations/"+org});
        expectations.put("organization.invitation.revoked",new String[]{"ORGANIZATION_INVITATION_REVOKED","/organizations/invitations"});
        expectations.put("organization.invitation.expired",new String[]{"ORGANIZATION_INVITATION_EXPIRED","/organizations/invitations"});
        expectations.put("organization.membership.revoked",new String[]{"ORGANIZATION_MEMBERSHIP_REVOKED","/organizations"});
        expectations.put("organization.membership.role-changed",new String[]{"ORGANIZATION_ROLE_CHANGED","/organizations/"+org});
        expectations.put("organization.ownership.transferred",new String[]{"ORGANIZATION_OWNERSHIP_TRANSFERRED","/organizations/"+org});
        for(var type:expectations.keySet()){
            UUID user=UUID.randomUUID(),event=UUID.randomUUID();recipients.put(type,user);events.put(type,event);
            // The producer link is deliberately wrong everywhere: the consumer's own route must win.
            var p=payload(user,extras.get(type));p.put("link","/organizations/somewhere/else");
            publish(event,type,p);
        }
        await(()->events.values().stream().allMatch(e->count(e)==1));
        for(var type:expectations.keySet()){
            UUID user=recipients.get(type);var row=db.queryForMap("SELECT type,metadata->>'link' AS link,recipient_user_id FROM notifications WHERE source_event_id=?",events.get(type));
            assertEquals(expectations.get(type)[0],row.get("type"),type);assertEquals(expectations.get(type)[1],row.get("link"),type);assertEquals(user,row.get("recipient_user_id"),type);
            mvc.perform(get("/notifications").header("Authorization",bearer(user))).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].type").value(expectations.get(type)[0]));
            mvc.perform(get("/notifications").header("Authorization",bearer(stranger))).andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Test void malformedOrderedOrHostileOrganizationEventsAreDeadLetteredWithoutRows()throws Exception{
        while(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)!=null){}
        UUID bad1=UUID.randomUUID(),bad2=UUID.randomUUID(),bad3=UUID.randomUUID(),bad4=UUID.randomUUID();
        publish(bad1,"organization.membership.role-changed",payload(invitee,Map.of("membershipId",membership.toString(),"role","OWNER")));
        publishRaw(Map.of("eventId",bad2.toString(),"eventType","organization.membership.revoked","eventVersion",2,"occurredAt","2026-10-08T01:00:00Z",
            "payload",payload(invitee,Map.of("membershipId",membership.toString()))),"organization.membership.revoked");
        publish(bad3,"organization.ownership.transferred",payload(invitee,Map.of("membershipId",membership.toString(),"link","https://evil.test/phish")));
        publish(bad4,"organization.invitation.created",payload(invitee,Map.of("invitationId",invitation.toString(),"role","MEMBER","invitedByUserId",inviter.toString(),"expiresAt","tomorrow")));
        int dead=0;for(int i=0;i<4;i++)if(rabbit.receive(RabbitMQConfig.DEAD_LETTER_QUEUE,10000)!=null)dead++;
        assertEquals(4,dead);
        for(UUID id:List.of(bad1,bad2,bad3,bad4))assertEquals(0,count(id));
        mvc.perform(get("/notifications").header("Authorization",bearer(invitee))).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void aRevokedMemberStillReadsTheirOwnInboxButNotAnotherUsers()throws Exception{
        UUID event=UUID.randomUUID();
        publish(event,"organization.membership.revoked",payload(invitee,Map.of("membershipId",membership.toString())));
        await(()->count(event)==1);
        mvc.perform(get("/notifications").header("Authorization",bearer(invitee))).andExpect(jsonPath("$.totalElements").value(1));
        assertEquals("Bạn không còn là thành viên của tổ chức \"Trường A\".",db.queryForObject("SELECT message FROM notifications WHERE source_event_id=?",String.class,event));
        String id=db.queryForObject("SELECT id::text FROM notifications WHERE source_event_id=?",String.class,event);
        mvc.perform(patch("/notifications/"+id+"/read").header("Authorization",bearer(stranger))).andExpect(status().isNotFound());
        mvc.perform(patch("/notifications/"+id+"/read").header("Authorization",bearer(invitee))).andExpect(status().isOk());
    }
}
