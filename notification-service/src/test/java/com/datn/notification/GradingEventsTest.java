package com.datn.notification;
import com.datn.notification.grading.GradingMessages;
import com.datn.notification.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class GradingEventsTest {
    ObjectMapper json=new ObjectMapper();
    UUID session=UUID.randomUUID(),exam=UUID.randomUUID(),student=UUID.randomUUID(),result=UUID.randomUUID();

    ObjectNode review(){var p=json.createObjectNode();p.put("resultId",result.toString()).put("sessionId",session.toString()).put("examId",exam.toString()).put("examTitle","Toán 12").put("resultRevision",1);return p;}
    ObjectNode regraded(){var p=json.createObjectNode();p.put("sessionId",session.toString()).put("examId",exam.toString()).put("examTitle","Toán 12").put("studentUserId",student.toString()).put("resultRevision",2);return p;}
    ObjectNode released(){var p=regraded();p.put("gradingMode","MANUAL");p.remove("resultRevision");return p;}
    ObjectNode envelope(String type,ObjectNode payload){
        var e=json.createObjectNode();e.put("eventId",UUID.randomUUID().toString()).put("eventType",type).put("eventVersion",1).put("occurredAt","2026-10-07T10:00:00Z");e.set("payload",payload);return e;
    }

    @Test void validPayloadsParseThroughTheEnvelope(){
        assertEquals(1,((GradingEvents.ReviewRequired)EventCatalog.envelope(envelope("grading.review.required",review())).scheduling()).resultRevision());
        var g=(GradingEvents.Regraded)EventCatalog.envelope(envelope("grading.result.regraded",regraded())).scheduling();assertEquals(2,g.resultRevision());assertEquals(student,g.studentUserId());
        var legacy=(GradingEvents.Released)EventCatalog.envelope(envelope("grading.result.released",released())).scheduling();assertNull(legacy.resultRevision());assertNull(legacy.resultId());
        var withRevision=released();withRevision.put("resultId",result.toString()).put("resultRevision",3);
        var r=(GradingEvents.Released)EventCatalog.envelope(envelope("grading.result.released",withRevision)).scheduling();assertEquals(3,r.resultRevision());assertEquals(result,r.resultId());
        // Extra fields (a producer mistake such as a score) are ignored, never stored or rendered.
        var extra=regraded();extra.put("score",9.5).put("answers","A").put("feedback","private");
        assertEquals("grading.result.regraded",EventCatalog.envelope(envelope("grading.result.regraded",extra)).eventType());
    }

    @Test void malformedPayloadsRejectedWithoutCoercion(){
        Map<String,Consumer<ObjectNode>> reviewBreakers=new LinkedHashMap<>();
        reviewBreakers.put("fraction",p->p.put("resultRevision",1.5));reviewBreakers.put("string",p->p.put("resultRevision","1"));reviewBreakers.put("zero",p->p.put("resultRevision",0));
        reviewBreakers.put("negative",p->p.put("resultRevision",-1));reviewBreakers.put("float one",p->p.put("resultRevision",1.0));reviewBreakers.put("missing revision",p->p.remove("resultRevision"));
        reviewBreakers.put("overflow",p->p.put("resultRevision",Long.MAX_VALUE).put("examId","x"));reviewBreakers.put("bad uuid",p->p.put("resultId","not-a-uuid"));
        reviewBreakers.put("missing result",p->p.remove("resultId"));reviewBreakers.put("missing title",p->p.remove("examTitle"));reviewBreakers.put("blank title",p->p.put("examTitle"," "));
        reviewBreakers.put("title not text",p->p.put("examTitle",5));reviewBreakers.put("missing session",p->p.remove("sessionId"));
        reviewBreakers.forEach((name,breaker)->{var p=review();breaker.accept(p);assertThrows(IllegalArgumentException.class,()->GradingEvents.reviewRequired(p),name);});
        Map<String,Consumer<ObjectNode>> regradeBreakers=new LinkedHashMap<>();
        regradeBreakers.put("fraction",p->p.put("resultRevision",2.5));regradeBreakers.put("zero",p->p.put("resultRevision",0));regradeBreakers.put("missing student",p->p.remove("studentUserId"));
        regradeBreakers.put("student not uuid",p->p.put("studentUserId","student"));regradeBreakers.put("missing revision",p->p.remove("resultRevision"));
        regradeBreakers.forEach((name,breaker)->{var p=regraded();breaker.accept(p);assertThrows(IllegalArgumentException.class,()->GradingEvents.regraded(p),name);});
        Map<String,Consumer<ObjectNode>> releasedBreakers=new LinkedHashMap<>();
        releasedBreakers.put("mode",p->p.put("gradingMode","DRAFT"));releasedBreakers.put("only revision",p->p.put("resultRevision",1));
        releasedBreakers.put("only result id",p->p.put("resultId",result.toString()));releasedBreakers.put("fractional revision",p->p.put("resultId",result.toString()).put("resultRevision",1.5));
        releasedBreakers.put("null result id",p->p.putNull("resultId").put("resultRevision",1));releasedBreakers.put("missing student",p->p.remove("studentUserId"));
        releasedBreakers.forEach((name,breaker)->{var p=released();breaker.accept(p);assertThrows(IllegalArgumentException.class,()->GradingEvents.released(p),name);});
        for(String type:List.of("grading.review.required","grading.result.regraded","grading.result.released")){
            for(JsonNodeCase c:List.of(new JsonNodeCase("string",json.getNodeFactory().textNode("x")),new JsonNodeCase("array",json.createArrayNode()),new JsonNodeCase("null",json.getNodeFactory().nullNode()))){
                var e=envelope(type,review());e.set("payload",c.node());assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(e),type+" "+c.name());
            }
            var v=envelope(type,type.endsWith("required")?review():regraded());v.put("eventVersion",2);assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(v));
            var t=envelope(type,type.endsWith("required")?review():regraded());t.remove("occurredAt");assertThrows(IllegalArgumentException.class,()->EventCatalog.envelope(t));
        }
    }
    record JsonNodeCase(String name,com.fasterxml.jackson.databind.JsonNode node){}

    @Test void contentHasLinksAndNoPrivateData(){
        var review=GradingMessages.reviewRequired("Toán \u0000 12",exam,session,result,1);
        assertEquals("/grading?examId="+exam,review.metadata().get("link"));assertEquals(Set.of("examTitle","sessionId","resultId","resultRevision","link"),review.metadata().keySet());
        var regrade=GradingMessages.regraded("Toán 12",session,2);assertEquals("/exam/result/"+session,regrade.metadata().get("link"));
        var first=GradingMessages.released("Toán 12",session,"AUTO",null);assertEquals("RESULT_AVAILABLE",first.type());assertFalse(first.metadata().containsKey("resultRevision"));
        for(var c:List.of(review,regrade,first)){
            String all=(c.title()+" "+c.message()+" "+c.metadata()).toLowerCase();
            for(String forbidden:List.of("score","answer","đáp án","feedback","joincode","token","studentuserid","điểm số","điểm:"))assertFalse(all.contains(forbidden),c.type()+" leaks "+forbidden+": "+all);
            assertFalse(all.matches("(?s).*\\d+([.,]\\d+)?\\s*(/\\s*\\d+|điểm|points?).*"),c.type()+" must not show a numeric score: "+all);
        }
    }
}
