package com.datn.notification.service;
import com.datn.notification.dto.NotificationDto.Draft;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Strict v1 mapping of the eight organization.* events (contracts/organization-events.md) to single-recipient inbox notices.
 * Recipient is the Auth user ID in the payload (no lookup). The SPA route is generated here from an allowlist and the
 * entity IDs; the producer's {@code link} is only shape-checked and never used. Other users' IDs, emails and tokens are
 * ignored and never stored. Invalid input throws IllegalArgumentException so the message is dead-lettered.
 */
public final class OrganizationEvents {
    public static final String INVITATION_CREATED="organization.invitation.created",INVITATION_ACCEPTED="organization.invitation.accepted",
        INVITATION_DECLINED="organization.invitation.declined",INVITATION_REVOKED="organization.invitation.revoked",INVITATION_EXPIRED="organization.invitation.expired",
        MEMBERSHIP_REVOKED="organization.membership.revoked",ROLE_CHANGED="organization.membership.role-changed",OWNERSHIP_TRANSFERRED="organization.ownership.transferred";
    public static final List<String> TYPES=List.of(INVITATION_CREATED,INVITATION_ACCEPTED,INVITATION_DECLINED,INVITATION_REVOKED,INVITATION_EXPIRED,
        MEMBERSHIP_REVOKED,ROLE_CHANGED,OWNERSHIP_TRANSFERRED);
    public static final int NAME_LIMIT=120,LINK_LIMIT=200;
    private static final Pattern SAFE_LINK=Pattern.compile("^/(?![/\\\\])[A-Za-z0-9/_\\-.?=&%]*$");
    private static final DateTimeFormatter EXPIRES=DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy",Locale.ROOT).withZone(ZoneOffset.UTC);
    private OrganizationEvents(){}

    public static boolean handles(String type){return TYPES.contains(type);}

    public static Draft draft(String type,JsonNode p){
        if(p==null || !p.isObject())throw new IllegalArgumentException("payload must be a JSON object");
        UUID recipient=EventCatalog.uuid(p,"recipientUserId"),org=EventCatalog.uuid(p,"organizationId");
        String name=name(p);checkProducerLink(p);
        String q="\""+name+"\"";
        var meta=new LinkedHashMap<String,String>();meta.put("organizationId",org.toString());meta.put("organizationName",name);
        String invitationsRoute="/organizations/invitations",orgRoute="/organizations/"+org;
        return switch(type){
            case INVITATION_CREATED -> {
                UUID invitation=EventCatalog.uuid(p,"invitationId");String role=role(p);EventCatalog.uuid(p,"invitedByUserId");
                Instant expires=instant(p,"expiresAt");
                meta.put("invitationId",invitation.toString());meta.put("role",role);meta.put("expiresAt",expires.toString());meta.put("link",invitationsRoute);
                yield draft(recipient,"ORGANIZATION_INVITATION_CREATED","Lời mời tham gia tổ chức",
                    "Bạn được mời tham gia tổ chức "+q+" với vai trò "+roleLabel(role)+". Lời mời hết hạn lúc "+EXPIRES.format(expires)+" UTC.","ORGANIZATION_INVITATION",invitation,meta);
            }
            case INVITATION_ACCEPTED -> {
                UUID invitation=EventCatalog.uuid(p,"invitationId");EventCatalog.uuid(p,"memberUserId");
                meta.put("invitationId",invitation.toString());meta.put("link",orgRoute);
                yield draft(recipient,"ORGANIZATION_INVITATION_ACCEPTED","Lời mời đã được chấp nhận","Lời mời tham gia tổ chức "+q+" của bạn đã được chấp nhận.","ORGANIZATION_INVITATION",invitation,meta);
            }
            case INVITATION_DECLINED -> {
                UUID invitation=EventCatalog.uuid(p,"invitationId");EventCatalog.uuid(p,"declinedByUserId");
                meta.put("invitationId",invitation.toString());meta.put("link",orgRoute);
                yield draft(recipient,"ORGANIZATION_INVITATION_DECLINED","Lời mời đã bị từ chối","Lời mời tham gia tổ chức "+q+" của bạn đã bị từ chối.","ORGANIZATION_INVITATION",invitation,meta);
            }
            case INVITATION_REVOKED -> {
                UUID invitation=EventCatalog.uuid(p,"invitationId");meta.put("invitationId",invitation.toString());meta.put("link",invitationsRoute);
                yield draft(recipient,"ORGANIZATION_INVITATION_REVOKED","Lời mời đã bị thu hồi","Lời mời tham gia tổ chức "+q+" đã bị thu hồi.","ORGANIZATION_INVITATION",invitation,meta);
            }
            case INVITATION_EXPIRED -> {
                UUID invitation=EventCatalog.uuid(p,"invitationId");meta.put("invitationId",invitation.toString());meta.put("link",invitationsRoute);
                yield draft(recipient,"ORGANIZATION_INVITATION_EXPIRED","Lời mời đã hết hạn","Lời mời tham gia tổ chức "+q+" đã hết hạn.","ORGANIZATION_INVITATION",invitation,meta);
            }
            case MEMBERSHIP_REVOKED -> {
                UUID membership=EventCatalog.uuid(p,"membershipId");meta.put("membershipId",membership.toString());meta.put("link","/organizations");
                yield draft(recipient,"ORGANIZATION_MEMBERSHIP_REVOKED","Bạn không còn là thành viên","Bạn không còn là thành viên của tổ chức "+q+".","ORGANIZATION",org,meta);
            }
            case ROLE_CHANGED -> {
                UUID membership=EventCatalog.uuid(p,"membershipId");String role=role(p);
                meta.put("membershipId",membership.toString());meta.put("role",role);meta.put("link",orgRoute);
                yield draft(recipient,"ORGANIZATION_ROLE_CHANGED","Vai trò trong tổ chức đã thay đổi","Vai trò của bạn trong tổ chức "+q+" đã được đổi thành "+roleLabel(role)+".","ORGANIZATION",org,meta);
            }
            case OWNERSHIP_TRANSFERRED -> {
                UUID membership=EventCatalog.uuid(p,"membershipId");meta.put("membershipId",membership.toString());meta.put("link",orgRoute);
                yield draft(recipient,"ORGANIZATION_OWNERSHIP_TRANSFERRED","Bạn là chủ sở hữu tổ chức mới","Bạn đã trở thành chủ sở hữu của tổ chức "+q+".","ORGANIZATION",org,meta);
            }
            default -> throw new IllegalArgumentException("Unsupported event type");
        };
    }
    private static Draft draft(UUID recipient,String type,String title,String message,String entityType,UUID entity,Map<String,String> meta){
        return new Draft(recipient,type,title,message,entityType,entity,Map.copyOf(meta));
    }
    /** Display text of at most NAME_LIMIT characters; control characters are replaced, longer or blank names are malformed. */
    static String name(JsonNode p){
        String n=EventCatalog.text(p,"organizationName").replaceAll("\\p{Cntrl}"," ").strip();
        if(n.isEmpty() || n.length()>NAME_LIMIT)throw new IllegalArgumentException("organizationName must be 1.."+NAME_LIMIT+" characters");
        return n;
    }
    static String role(JsonNode p){return EventCatalog.oneOf(p,"role","ADMIN","MEMBER");}
    static String roleLabel(String role){return role.equals("ADMIN")?"Quản trị viên":"Thành viên";}
    static Instant instant(JsonNode p,String field){
        try{return Instant.parse(EventCatalog.text(p,field));}catch(DateTimeParseException e){throw new IllegalArgumentException(field+" must be an ISO-8601 instant");}
    }
    /** The producer link is a contract field but never a redirect source: it must merely be a harmless relative path. */
    static void checkProducerLink(JsonNode p){
        if(!p.has("link"))return;
        JsonNode v=p.get("link");
        if(!v.isTextual() || v.asText().length()>LINK_LIMIT || !SAFE_LINK.matcher(v.asText()).matches())throw new IllegalArgumentException("link must be a relative SPA route");
    }
}
