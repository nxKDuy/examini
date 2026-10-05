package com.datn.notification;
import com.datn.notification.service.EventCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EventCatalogTest {
    ObjectMapper json=new ObjectMapper();
    Map<String,Object> submitted(String kind,String title){return Map.of("sessionId",UUID.randomUUID().toString(),"examId",UUID.randomUUID().toString(),"examTitle",title,"studentUserId",UUID.randomUUID().toString(),"submissionType",kind);}
    @Test void autoSubmissionWordingAndTitleSanitized(){
        var d=EventCatalog.draft("session.submitted",json.valueToTree(submitted("AUTO_SUBMITTED","A\nB"+"x".repeat(300))));
        assertEquals("SUBMISSION_RECEIVED",d.type());assertEquals("EXAM_SESSION",d.entityType());
        assertTrue(d.message().startsWith("Hết giờ"));assertFalse(d.metadata().get("examTitle").contains("\n"));assertEquals(150,d.metadata().get("examTitle").length());
    }
    @Test void unsupportedValuesAndMissingFieldsRejected(){
        assertThrows(IllegalArgumentException.class,()->EventCatalog.draft("session.submitted",json.valueToTree(submitted("CANCELLED","T"))));
        assertThrows(IllegalArgumentException.class,()->EventCatalog.draft("session.submitted",json.valueToTree(submitted("SUBMITTED"," "))));
        assertThrows(IllegalArgumentException.class,()->EventCatalog.draft("question.reviewed",json.valueToTree(Map.of("questionId",UUID.randomUUID().toString(),"questionVersion",0,"ownerUserId",UUID.randomUUID().toString(),"decision","APPROVED"))));
        assertThrows(IllegalArgumentException.class,()->EventCatalog.draft("exam.member.invited",json.valueToTree(Map.of("examId",UUID.randomUUID().toString(),"examTitle","T","userId",UUID.randomUUID().toString(),"role","OWNER"))));
        assertThrows(IllegalArgumentException.class,()->EventCatalog.draft("grading.result.released",null));
    }
    ObjectNode valid(){
        return json.valueToTree(Map.of("eventId",UUID.randomUUID().toString(),"eventType","question.reviewed","eventVersion",1,"occurredAt","2026-10-05T10:00:00Z",
            "payload",Map.of("questionId",UUID.randomUUID().toString(),"questionVersion",2,"ownerUserId",UUID.randomUUID().toString(),"decision","REJECTED")));
    }
    @Test void validEnvelopeParsesInstantAndIntegers(){
        var e=EventCatalog.envelope(valid());
        assertEquals(Instant.parse("2026-10-05T10:00:00Z"),e.occurredAt());assertEquals("QUESTION_REJECTED",e.draft().type());assertEquals("2",e.draft().metadata().get("questionVersion"));
        var offset=valid();offset.put("occurredAt","2026-10-05T17:00:00+07:00");assertEquals(Instant.parse("2026-10-05T10:00:00Z"),EventCatalog.envelope(offset).occurredAt());
    }
    @Test void malformedEnvelopesRejectedWithoutCoercion()throws Exception{
        List<java.util.function.Consumer<ObjectNode>> broken=List.of(
            e->e.put("eventVersion",1.5),e->e.put("eventVersion",1.0),e->e.put("eventVersion","1"),e->e.put("eventVersion",true),e->e.put("eventVersion",2),e->e.remove("eventVersion"),
            e->e.put("eventVersion",4294967297L),
            e->e.remove("occurredAt"),e->e.put("occurredAt",""),e->e.put("occurredAt","2026-10-05"),e->e.put("occurredAt","2026-10-05T10:00:00"),e->e.put("occurredAt","yesterday"),e->e.put("occurredAt",1759658400),
            e->((ObjectNode)e.get("payload")).put("questionVersion",1.5),e->((ObjectNode)e.get("payload")).put("questionVersion","2"),e->((ObjectNode)e.get("payload")).put("questionVersion",2.0),
            e->e.put("eventId","not-a-uuid"),e->e.remove("eventId"),e->e.put("eventType","exam.deleted"),e->e.put("payload","text"),e->e.remove("payload"));
        for(int i=0;i<broken.size();i++){var e=valid();broken.get(i).accept(e);int n=i;assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(e),"case "+n+": "+e);}
        for(String raw:List.of("[]","\"text\"","null","1"))assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(json.readTree(raw)),raw);
        assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(null));
    }
}
