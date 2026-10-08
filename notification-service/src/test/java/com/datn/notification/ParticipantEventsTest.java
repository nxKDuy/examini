package com.datn.notification;
import com.datn.notification.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ParticipantEventsTest {
    ObjectMapper json=new ObjectMapper();
    UUID exam=UUID.randomUUID(),student=UUID.randomUUID(),session=UUID.randomUUID(),teacher=UUID.randomUUID();

    ObjectNode changed(String cause){
        var p=json.createObjectNode();
        p.put("examId",exam.toString()).put("examTitle","Toán 12").put("userId",student.toString()).put("cause",cause).put("actorUserId",teacher.toString());
        p.putNull("quantity");p.putNull("publicReason");p.put("recipientRevision",3).put("registeredCount",10).put("eligibleCount",9).put("revokedCount",1);
        return p;
    }
    ObjectNode invalidated(String status){
        var p=json.createObjectNode();
        p.put("sessionId",session.toString()).put("examId",exam.toString()).put("userId",student.toString()).put("attemptNumber",2).put("status",status)
         .put("reason","Gian lận đã xác nhận").put("actor",teacher.toString()).put("invalidatedAt","2026-10-08T03:00:00Z");
        return p;
    }
    ObjectNode envelope(String type,ObjectNode payload){
        var e=json.createObjectNode();e.put("eventId",UUID.randomUUID().toString()).put("eventType",type).put("eventVersion",1).put("occurredAt","2026-10-08T03:00:00Z");e.set("payload",payload);return e;
    }

    @Test void participantCausesMapToStudentNoticesWithGeneratedRoutes(){
        var assigned=EventCatalog.envelope(envelope("exam.participant.changed",changed("ASSIGNED"))).draft();
        assertEquals(student,assigned.recipient());assertEquals("PARTICIPANT_ASSIGNED",assigned.type());assertEquals("EXAM",assigned.entityType());assertEquals(exam,assigned.entityId());
        assertEquals("/exam/lobby/"+exam,assigned.metadata().get("link"));assertEquals("Bạn được giao kỳ thi \"Toán 12\".",assigned.message());
        var back=EventCatalog.draft("exam.participant.changed",changed("REINSTATED"));
        assertEquals("PARTICIPANT_REINSTATED",back.type());assertEquals("/exam/lobby/"+exam,back.metadata().get("link"));
        var revoked=changed("REVOKED");revoked.put("publicReason","Vắng mặt buổi ôn tập");
        var r=EventCatalog.draft("exam.participant.changed",revoked);
        assertEquals("PARTICIPANT_REVOKED",r.type());assertEquals("/my-exams",r.metadata().get("link"));assertTrue(r.message().endsWith("đã bị thu hồi. Lý do: Vắng mặt buổi ôn tập"),r.message());
        assertEquals("Quyền dự thi kỳ thi \"Toán 12\" của bạn đã bị thu hồi.",EventCatalog.draft("exam.participant.changed",changed("REVOKED")).message(),"no reason, no suffix");
        var grant=changed("ATTEMPT_GRANTED");grant.put("quantity",2).put("publicReason","Sự cố mạng");
        var g=EventCatalog.draft("exam.participant.changed",grant);
        assertEquals("ATTEMPT_GRANTED",g.type());assertEquals("Bạn được cấp thêm 2 lượt làm bài kỳ thi \"Toán 12\". Lý do: Sự cố mạng",g.message());
        assertEquals(Set.of("examId","examTitle","cause","quantity","link"),g.metadata().keySet());assertEquals("2",g.metadata().get("quantity"));
        assertEquals(Set.of("examId","examTitle","cause","link"),assigned.metadata().keySet());
        // Students join themselves: valid, but silent.
        var joined=EventCatalog.envelope(envelope("exam.participant.changed",changed("JOINED")));assertNull(joined.draft());assertNull(joined.scheduling());
    }

    @Test void attemptInvalidationRoutesToResultOrExamAndShowsOnlyThePublicReason(){
        var submitted=EventCatalog.envelope(envelope("exam.attempt.invalidated",invalidated("SUBMITTED"))).draft();
        assertEquals(student,submitted.recipient());assertEquals("ATTEMPT_INVALIDATED",submitted.type());assertEquals("EXAM_SESSION",submitted.entityType());assertEquals(session,submitted.entityId());
        assertEquals("/exam/result/"+session,submitted.metadata().get("link"));
        assertEquals("Lượt làm bài số 2 của bạn đã bị vô hiệu hóa. Lý do: Gian lận đã xác nhận",submitted.message());
        assertEquals(Set.of("examId","sessionId","attemptNumber","status","link"),submitted.metadata().keySet());
        assertEquals("/exam/result/"+session,EventCatalog.draft("exam.attempt.invalidated",invalidated("AUTO_SUBMITTED")).metadata().get("link"));
        assertEquals("/exam/lobby/"+exam,EventCatalog.draft("exam.attempt.invalidated",invalidated("CANCELLED")).metadata().get("link"),"a cancelled attempt has no result page");
        var none=invalidated("SUBMITTED");none.putNull("reason");none.putNull("actor");
        assertEquals("Lượt làm bài số 2 của bạn đã bị vô hiệu hóa.",EventCatalog.draft("exam.attempt.invalidated",none).message());
        for(var d:List.of(submitted)){String all=(d.message()+d.metadata()).toLowerCase();assertFalse(all.contains(teacher.toString()),"actor ID not shown");assertFalse(all.contains("2026-10-08"));}
    }

    @Test void privateFieldsAreIgnoredAndReasonTextIsSanitized(){
        var p=changed("REVOKED");p.put("publicReason","a\u0000b\nc "+"x".repeat(400));p.put("email","s@example.test").put("token","SECRET").put("privateNote","n").put("score",9.5);
        var d=EventCatalog.draft("exam.participant.changed",p);
        String all=d.title()+d.message()+d.metadata();
        for(String leaked:List.of("s@example.test","SECRET","privateNote","9.5",teacher.toString()))assertFalse(all.contains(leaked),leaked);
        assertFalse(d.message().chars().anyMatch(Character::isISOControl));assertTrue(d.message().length()<500);assertTrue(d.message().contains("a b c"));
        var blank=changed("REVOKED");blank.put("publicReason","  \n ");assertFalse(EventCatalog.draft("exam.participant.changed",blank).message().contains("Lý do"));
    }

    @Test void malformedPayloadsAreRejected(){
        Map<String,Consumer<ObjectNode>> changedBreakers=new LinkedHashMap<>();
        changedBreakers.put("cause lowercase",p->p.put("cause","assigned"));changedBreakers.put("cause unknown",p->p.put("cause","REGISTERED"));changedBreakers.put("cause missing",p->p.remove("cause"));
        changedBreakers.put("user not uuid",p->p.put("userId","u"));changedBreakers.put("user missing",p->p.remove("userId"));changedBreakers.put("exam not uuid",p->p.put("examId","e"));
        changedBreakers.put("title missing",p->p.remove("examTitle"));changedBreakers.put("actor not uuid",p->p.put("actorUserId","a"));
        changedBreakers.put("revision fraction",p->p.put("recipientRevision",1.5));changedBreakers.put("revision negative",p->p.put("recipientRevision",-1));changedBreakers.put("revision missing",p->p.remove("recipientRevision"));
        changedBreakers.put("count string",p->p.put("eligibleCount","9"));changedBreakers.put("count negative",p->p.put("revokedCount",-1));changedBreakers.put("count missing",p->p.remove("registeredCount"));
        changedBreakers.put("quantity zero",p->p.put("quantity",0));changedBreakers.put("quantity 101",p->p.put("quantity",101));changedBreakers.put("quantity fraction",p->p.put("quantity",1.5));changedBreakers.put("quantity string",p->p.put("quantity","2"));
        changedBreakers.put("reason not text",p->p.put("publicReason",5));
        changedBreakers.forEach((name,b)->{var p=changed("ASSIGNED");b.accept(p);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope("exam.participant.changed",p)),name);});
        var grantNoQuantity=changed("ATTEMPT_GRANTED");assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope("exam.participant.changed",grantNoQuantity)),"ATTEMPT_GRANTED needs quantity");
        Map<String,Consumer<ObjectNode>> invalidatedBreakers=new LinkedHashMap<>();
        invalidatedBreakers.put("session not uuid",p->p.put("sessionId","s"));invalidatedBreakers.put("user missing",p->p.remove("userId"));invalidatedBreakers.put("exam missing",p->p.remove("examId"));
        invalidatedBreakers.put("attempt zero",p->p.put("attemptNumber",0));invalidatedBreakers.put("attempt fraction",p->p.put("attemptNumber",1.5));invalidatedBreakers.put("attempt string",p->p.put("attemptNumber","1"));
        invalidatedBreakers.put("status unknown",p->p.put("status","DELETED"));invalidatedBreakers.put("status missing",p->p.remove("status"));invalidatedBreakers.put("actor not uuid",p->p.put("actor","teacher"));
        invalidatedBreakers.put("time missing",p->p.remove("invalidatedAt"));invalidatedBreakers.put("time not instant",p->p.put("invalidatedAt","2026-10-08 03:00"));invalidatedBreakers.put("time epoch",p->p.put("invalidatedAt",1760000000));
        invalidatedBreakers.put("reason not text",p->p.put("reason",true));
        invalidatedBreakers.forEach((name,b)->{var p=invalidated("SUBMITTED");b.accept(p);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(envelope("exam.attempt.invalidated",p)),name);});
        for(String type:ParticipantEvents.TYPES){
            ObjectNode payload=type.endsWith("changed")?changed("ASSIGNED"):invalidated("SUBMITTED");
            var v=envelope(type,payload.deepCopy());v.put("eventVersion",2);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(v));
            var f=envelope(type,payload.deepCopy());f.put("eventVersion",1.5);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(f));
            var t=envelope(type,payload.deepCopy());t.remove("occurredAt");assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(t));
            var a=envelope(type,payload.deepCopy());a.set("payload",json.createArrayNode());assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(a));
        }
    }

    @Test void legacyAssignedEventStillWorks(){
        var p=json.createObjectNode();p.put("examId",exam.toString()).put("examTitle","Toán 12").put("userId",student.toString());
        assertEquals("EXAM_ASSIGNED",EventCatalog.envelope(envelope("exam.participant.assigned",p)).draft().type());
    }
}
