package com.datn.notification;
import com.datn.notification.service.EventCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
}
