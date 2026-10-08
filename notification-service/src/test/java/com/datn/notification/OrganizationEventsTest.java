package com.datn.notification;
import com.datn.notification.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationEventsTest {
    ObjectMapper json=new ObjectMapper();
    UUID recipient=UUID.randomUUID(),org=UUID.randomUUID(),invitation=UUID.randomUUID(),membership=UUID.randomUUID(),other=UUID.randomUUID();

    ObjectNode payload(String type){
        var p=json.createObjectNode();
        p.put("recipientUserId",recipient.toString()).put("organizationId",org.toString()).put("organizationName","Trường THPT Lê Quý Đôn").put("link","/organizations/invitations");
        switch(type){
            case OrganizationEvents.INVITATION_CREATED -> p.put("invitationId",invitation.toString()).put("role","ADMIN").put("invitedByUserId",other.toString()).put("expiresAt","2026-10-15T08:30:00Z");
            case OrganizationEvents.INVITATION_ACCEPTED -> p.put("invitationId",invitation.toString()).put("memberUserId",other.toString());
            case OrganizationEvents.INVITATION_DECLINED -> p.put("invitationId",invitation.toString()).put("declinedByUserId",other.toString());
            case OrganizationEvents.INVITATION_REVOKED,OrganizationEvents.INVITATION_EXPIRED -> p.put("invitationId",invitation.toString());
            case OrganizationEvents.MEMBERSHIP_REVOKED,OrganizationEvents.OWNERSHIP_TRANSFERRED -> p.put("membershipId",membership.toString());
            case OrganizationEvents.ROLE_CHANGED -> p.put("membershipId",membership.toString()).put("role","MEMBER");
            default -> throw new IllegalArgumentException(type);
        }
        return p;
    }
    ObjectNode envelope(String type,ObjectNode payload){
        var e=json.createObjectNode();e.put("eventId",UUID.randomUUID().toString()).put("eventType",type).put("eventVersion",1).put("occurredAt","2026-10-08T01:00:00Z");e.set("payload",payload);return e;
    }

    @Test void allEightEventsMapToTheRightRecipientEntityAndAllowlistedRoute(){
        record Expect(String type,String notice,String entityType,String route,Set<String> keys){}
        Set<String> base=Set.of("organizationId","organizationName","link");
        var cases=List.of(
            new Expect(OrganizationEvents.INVITATION_CREATED,"ORGANIZATION_INVITATION_CREATED","ORGANIZATION_INVITATION","/organizations/invitations",union(base,"invitationId","role","expiresAt")),
            new Expect(OrganizationEvents.INVITATION_ACCEPTED,"ORGANIZATION_INVITATION_ACCEPTED","ORGANIZATION_INVITATION","/organizations/"+org,union(base,"invitationId")),
            new Expect(OrganizationEvents.INVITATION_DECLINED,"ORGANIZATION_INVITATION_DECLINED","ORGANIZATION_INVITATION","/organizations/"+org,union(base,"invitationId")),
            new Expect(OrganizationEvents.INVITATION_REVOKED,"ORGANIZATION_INVITATION_REVOKED","ORGANIZATION_INVITATION","/organizations/invitations",union(base,"invitationId")),
            new Expect(OrganizationEvents.INVITATION_EXPIRED,"ORGANIZATION_INVITATION_EXPIRED","ORGANIZATION_INVITATION","/organizations/invitations",union(base,"invitationId")),
            new Expect(OrganizationEvents.MEMBERSHIP_REVOKED,"ORGANIZATION_MEMBERSHIP_REVOKED","ORGANIZATION","/organizations",union(base,"membershipId")),
            new Expect(OrganizationEvents.ROLE_CHANGED,"ORGANIZATION_ROLE_CHANGED","ORGANIZATION","/organizations/"+org,union(base,"membershipId","role")),
            new Expect(OrganizationEvents.OWNERSHIP_TRANSFERRED,"ORGANIZATION_OWNERSHIP_TRANSFERRED","ORGANIZATION","/organizations/"+org,union(base,"membershipId")));
        assertEquals(8,cases.size());assertEquals(8,OrganizationEvents.TYPES.size());
        for(var c:cases){
            var e=EventCatalog.envelope(envelope(c.type(),payload(c.type())));
            var d=e.draft();assertNotNull(d,c.type());assertNull(e.scheduling());
            assertEquals(recipient,d.recipient(),c.type());assertEquals(c.notice(),d.type());assertEquals(c.entityType(),d.entityType());
            assertEquals(c.route(),d.metadata().get("link"),"route is generated from the allowlist: "+c.type());
            assertEquals(c.keys(),d.metadata().keySet(),"metadata allowlist for "+c.type());
            assertEquals(c.entityType().equals("ORGANIZATION")?org:invitation,d.entityId());
            assertTrue(d.message().contains("\"Trường THPT Lê Quý Đôn\""),d.message());
            assertTrue(d.title().length()<=200 && d.message().length()<=1000);
        }
        // Wording details.
        var created=EventCatalog.draft(OrganizationEvents.INVITATION_CREATED,payload(OrganizationEvents.INVITATION_CREATED));
        assertEquals("Bạn được mời tham gia tổ chức \"Trường THPT Lê Quý Đôn\" với vai trò Quản trị viên. Lời mời hết hạn lúc 08:30 15/10/2026 UTC.",created.message());
        assertTrue(EventCatalog.draft(OrganizationEvents.ROLE_CHANGED,payload(OrganizationEvents.ROLE_CHANGED)).message().endsWith("đổi thành Thành viên."));
    }
    static Set<String> union(Set<String> a,String... more){var s=new HashSet<>(a);s.addAll(Arrays.asList(more));return s;}

    @Test void hostileProducerLinkAndPrivateFieldsAreNeverStoredOrFollowed(){
        var p=payload(OrganizationEvents.INVITATION_CREATED);
        p.put("email","victim@example.test").put("token","SECRET-TOKEN").put("privateNote","note").put("score",9.5).put("answerKey","A");
        var d=EventCatalog.draft(OrganizationEvents.INVITATION_CREATED,p);
        String all=d.title()+d.message()+d.metadata()+d.entityType();
        for(String leaked:List.of("victim@example.test","SECRET-TOKEN","note","9.5","answerKey"))assertFalse(all.contains(leaked),leaked);
        assertFalse(d.metadata().containsKey("invitedByUserId"),"other users' IDs are not stored");
        for(String hostile:List.of("https://evil.test/x","//evil.test/x","javascript:alert(1)","/\\evil.test","/organizations/<script>","/x y","relative/no/slash",""))
            { p.put("link",hostile);ObjectNode copy=p;assertThrows(IllegalArgumentException.class,()->EventCatalog.draft(OrganizationEvents.INVITATION_CREATED,copy),hostile); }
        p.put("link","/"+"a".repeat(OrganizationEvents.LINK_LIMIT));assertThrows(IllegalArgumentException.class,()->EventCatalog.draft(OrganizationEvents.INVITATION_CREATED,p));
        p.remove("link");assertEquals("/organizations/invitations",EventCatalog.draft(OrganizationEvents.INVITATION_CREATED,p).metadata().get("link"),"a missing producer link is tolerated; ours is used");
        p.put("link","/organizations/"+UUID.randomUUID());assertEquals("/organizations/invitations",EventCatalog.draft(OrganizationEvents.INVITATION_CREATED,p).metadata().get("link"),"even a valid producer link is not used");
    }

    @Test void displayTextIsBoundedAndSanitized(){
        var p=payload(OrganizationEvents.MEMBERSHIP_REVOKED);
        p.put("organizationName","Tổ\u0000chức\n\"Lạ\"");
        var d=EventCatalog.draft(OrganizationEvents.MEMBERSHIP_REVOKED,p);
        assertFalse(d.message().chars().anyMatch(Character::isISOControl));assertTrue(d.message().contains("Tổ chức \"Lạ\""));
        p.put("organizationName","x".repeat(OrganizationEvents.NAME_LIMIT));assertDoesNotThrow(()->EventCatalog.draft(OrganizationEvents.MEMBERSHIP_REVOKED,p));
        p.put("organizationName","x".repeat(OrganizationEvents.NAME_LIMIT+1));assertThrows(IllegalArgumentException.class,()->EventCatalog.draft(OrganizationEvents.MEMBERSHIP_REVOKED,p));
        for(String bad:List.of(" ","\n\t"))assertThrows(IllegalArgumentException.class,()->{p.put("organizationName",bad);EventCatalog.draft(OrganizationEvents.MEMBERSHIP_REVOKED,p);});
    }

    @Test void malformedEnvelopesAndPayloadsRejected(){
        Map<String,Consumer<ObjectNode>> breakers=new LinkedHashMap<>();
        breakers.put("recipient not uuid",p->p.put("recipientUserId","user-1"));breakers.put("missing recipient",p->p.remove("recipientUserId"));
        breakers.put("recipient null",p->p.putNull("recipientUserId"));breakers.put("org not uuid",p->p.put("organizationId","org"));breakers.put("missing org",p->p.remove("organizationId"));
        breakers.put("missing name",p->p.remove("organizationName"));breakers.put("name not text",p->p.put("organizationName",12));breakers.put("name null",p->p.putNull("organizationName"));
        breakers.put("link not text",p->p.put("link",5));
        for(String type:OrganizationEvents.TYPES)
            breakers.forEach((name,breaker)->{var p=payload(type);breaker.accept(p);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope(type,p)),type+" / "+name);});
        Map<String,Map<String,Consumer<ObjectNode>>> perType=Map.of(
            OrganizationEvents.INVITATION_CREATED,Map.of("role OWNER",p->p.put("role","OWNER"),"role lowercase",p->p.put("role","admin"),"role missing",p->p.remove("role"),
                "expires date only",p->p.put("expiresAt","2026-10-15"),"expires no zone",p->p.put("expiresAt","2026-10-15T08:30:00"),"expires epoch",p->p.put("expiresAt",1760000000),
                "expires missing",p->p.remove("expiresAt"),"invitation not uuid",p->p.put("invitationId","1"),"inviter missing",p->p.remove("invitedByUserId")),
            OrganizationEvents.INVITATION_ACCEPTED,Map.of("member missing",p->p.remove("memberUserId"),"member not uuid",p->p.put("memberUserId","m"),"invitation missing",p->p.remove("invitationId")),
            OrganizationEvents.INVITATION_DECLINED,Map.of("decliner missing",p->p.remove("declinedByUserId"),"invitation not uuid",p->p.put("invitationId","x")),
            OrganizationEvents.INVITATION_REVOKED,Map.of("invitation missing",p->p.remove("invitationId")),
            OrganizationEvents.INVITATION_EXPIRED,Map.of("invitation null",p->p.putNull("invitationId")),
            OrganizationEvents.MEMBERSHIP_REVOKED,Map.of("membership missing",p->p.remove("membershipId")),
            OrganizationEvents.ROLE_CHANGED,Map.of("role OWNER",p->p.put("role","OWNER"),"role number",p->p.put("role",1),"membership not uuid",p->p.put("membershipId","m")),
            OrganizationEvents.OWNERSHIP_TRANSFERRED,Map.of("membership not uuid",p->p.put("membershipId","m")));
        perType.forEach((type,cases)->cases.forEach((name,breaker)->{var p=payload(type);breaker.accept(p);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope(type,p)),type+" / "+name);}));
        for(String type:OrganizationEvents.TYPES){
            var v2=envelope(type,payload(type));v2.put("eventVersion",2);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(v2));
            var frac=envelope(type,payload(type));frac.put("eventVersion",1.5);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(frac));
            var noTime=envelope(type,payload(type));noTime.remove("occurredAt");assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(noTime));
            var badId=envelope(type,payload(type));badId.put("eventId","e");assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(badId));
            var arr=envelope(type,payload(type));arr.set("payload",json.createArrayNode());assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(arr));
        }
        assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope("organization.invitation.deleted",payload(OrganizationEvents.INVITATION_REVOKED))),"unsupported organization type");
        assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope("organization.member.added",payload(OrganizationEvents.INVITATION_REVOKED))));
    }
}
