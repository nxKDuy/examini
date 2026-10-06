package com.datn.notification.delivery;
import com.datn.notification.client.ExamRecipientClient.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authority re-check applied to every queued email/push before each send or retry. */
class DeliveryAuthorityTest {
    UUID exam=UUID.randomUUID(),student=UUID.randomUUID(),manager=UUID.randomUUID();
    Instant start=Instant.parse("2026-10-07T01:00:00Z"),now=start.minus(Duration.ofMinutes(20));
    Snapshot snap(String status,long rev,Instant startAt,List<Integer> offsets,boolean submitted){
        return new Snapshot(exam,"T",status,rev,1,1,startAt,null,"UTC","FULL_DURATION",offsets,
            List.of(new Recipient(student,"ASSIGNED",submitted,submitted),new Recipient(manager,"MANAGER",false,false)));
    }
    DeliveryWorker.Row row(String type,UUID user,String audience,Map<String,String> extra){
        var m=new HashMap<String,String>(extra);if(audience!=null)m.put("audience",audience);
        return new DeliveryWorker.Row(UUID.randomUUID(),"EMAIL",user,null,1,start,UUID.randomUUID(),type,"t","m",exam,m);
    }
    DeliveryWorker.Row reminder(long rev){return row("EXAM_REMINDER",student,"STUDENT",Map.of("scheduleRevision",String.valueOf(rev),"startAt",start.toString(),"offsetMinutes","30"));}

    @Test void reminderValidOnlyForSameStartOffsetOpenScheduleBeforeStart(){
        assertNull(DeliveryWorker.staleReason(reminder(2),snap("PUBLISHED",2,start,List.of(30),false),now));
        assertNull(DeliveryWorker.staleReason(reminder(2),snap("PUBLISHED",3,start,List.of(30,15),false),now),"endAt-only revision keeps it valid");
        assertEquals("LAG",DeliveryWorker.staleReason(reminder(2),snap("PUBLISHED",1,start,List.of(30),false),now));
        assertEquals("SCHEDULE_CHANGED",DeliveryWorker.staleReason(reminder(2),snap("PUBLISHED",3,start.plusSeconds(60),List.of(30),false),now));
        assertEquals("SCHEDULE_CHANGED",DeliveryWorker.staleReason(reminder(2),snap("PUBLISHED",3,start,List.of(15),false),now),"offset removed");
        assertEquals("SCHEDULE_CHANGED",DeliveryWorker.staleReason(reminder(2),snap("CLOSED",2,start,List.of(30),false),now));
        assertEquals("SCHEDULE_CHANGED",DeliveryWorker.staleReason(reminder(2),snap("PUBLISHED",2,start,List.of(30),false),start));
    }
    @Test void recipientMustStillHoldTheSameKindOfRelation(){
        assertEquals("RECIPIENT_REMOVED",DeliveryWorker.staleReason(row("EXAM_SCHEDULED",UUID.randomUUID(),"STUDENT",Map.of()),snap("PUBLISHED",1,start,List.of(),false),now));
        assertEquals("RECIPIENT_REMOVED",DeliveryWorker.staleReason(row("EXAM_SCHEDULED",student,"MANAGER",Map.of()),snap("PUBLISHED",1,start,List.of(),false),now));
        assertEquals("RECIPIENT_REMOVED",DeliveryWorker.staleReason(row("EXAM_CLOSED",student,"STUDENT",Map.of()),snap("CLOSED",1,start,List.of(),true),now),"submitted students");
        assertNull(DeliveryWorker.staleReason(row("EXAM_OPENED",student,"STUDENT",Map.of()),snap("OPEN",1,start,List.of(),true),now),"Open still reaches submitted students");
        assertEquals("UNVERIFIABLE",DeliveryWorker.staleReason(row("EXAM_SCHEDULED",student,null,Map.of()),snap("PUBLISHED",1,start,List.of(),false),now));
        assertNull(DeliveryWorker.staleReason(row("EXAM_UNSCHEDULED",manager,"MANAGER",Map.of()),snap("DRAFT",2,null,List.of(),false),now));
        assertEquals("RECIPIENT_REMOVED",DeliveryWorker.staleReason(row("EXAM_SCHEDULED",student,"STUDENT",Map.of()),snap("DRAFT",1,start,List.of(),false),now));
    }
    @Test void openAndCloseNoticesSupersededByLaterLifecycle(){
        assertEquals("SUPERSEDED",DeliveryWorker.staleReason(row("EXAM_OPENED",manager,"MANAGER",Map.of()),snap("CLOSED",1,start,List.of(),false),now));
        assertEquals("SUPERSEDED",DeliveryWorker.staleReason(row("EXAM_CLOSED",manager,"MANAGER",Map.of()),snap("OPEN",1,start,List.of(),false),now));
        assertNull(DeliveryWorker.staleReason(row("EXAM_CLOSED",manager,"MANAGER",Map.of()),snap("CLOSED",1,start,List.of(),false),now));
    }
}
