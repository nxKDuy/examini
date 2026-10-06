package com.datn.notification.scheduling;
import com.datn.notification.client.ExamRecipientClient.*;
import com.datn.notification.service.SchedulingEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingRulesTest {
    ObjectMapper json=new ObjectMapper();
    UUID owner=UUID.randomUUID(),manager=UUID.randomUUID(),assigned=UUID.randomUUID(),joined=UUID.randomUUID(),follower=UUID.randomUUID(),submitted=UUID.randomUUID();
    Snapshot snap(){return new Snapshot(UUID.randomUUID(),"T","PUBLISHED",1,1,0,null,null,"UTC","FULL_DURATION",List.of(),List.of(
        new Recipient(owner,"OWNER",false,false),new Recipient(manager,"MANAGER",false,false),new Recipient(assigned,"ASSIGNED",false,false),
        new Recipient(joined,"JOINED",true,false),new Recipient(follower,"FOLLOWER",false,false),new Recipient(submitted,"ASSIGNED",true,true)));}
    Set<UUID> ids(List<SchedulingJobs.Target> t){var s=new HashSet<UUID>();t.forEach(x->s.add(x.userId()));assertEquals(s.size(),t.size(),"one notification per user");return s;}

    @Test void actorExcludedDraftManagersOnlyAndSubmittedFilter(){
        assertEquals(Set.of(manager),ids(SchedulingJobs.targets(snap(),owner,false,true)));
        assertEquals(Set.of(manager,assigned,joined,follower),ids(SchedulingJobs.targets(snap(),owner,true,true)));
        assertEquals(Set.of(owner,manager,assigned,joined,follower,submitted),ids(SchedulingJobs.targets(snap(),null,true,false)));
    }
    @Test void examTimezoneWithOffsetLabelAcrossDst(){
        assertEquals("08:00 06/10/2026 (GMT+07:00, Asia/Ho_Chi_Minh)",SchedulingMessages.time(Instant.parse("2026-10-06T01:00:00Z"),"Asia/Ho_Chi_Minh"));
        assertEquals("09:00 07/03/2026 (GMT-05:00, America/New_York)",SchedulingMessages.time(Instant.parse("2026-03-07T14:00:00Z"),"America/New_York"));
        assertEquals("09:00 09/03/2026 (GMT-04:00, America/New_York)",SchedulingMessages.time(Instant.parse("2026-03-09T13:00:00Z"),"America/New_York"));
        assertEquals("1 ngày",SchedulingMessages.duration(1440));assertEquals("15 phút",SchedulingMessages.duration(15));assertEquals("1 giờ 30 phút",SchedulingMessages.duration(90));
    }
    @Test void contentHasTitleTimeRuleButNoPrivateFields(){
        var s=new SchedulingMessages.Schedule("Toán 12",Instant.parse("2026-10-06T01:00:00Z"),Instant.parse("2026-10-06T03:00:00Z"),"Asia/Ho_Chi_Minh","CAPPED_AT_END");
        var c=SchedulingMessages.reminder(s,15);
        assertEquals("EXAM_REMINDER",c.type());
        assertTrue(c.message().contains("Còn 15 phút nữa kỳ thi \"Toán 12\" bắt đầu") && c.message().contains("08:00 06/10/2026 (GMT+07:00, Asia/Ho_Chi_Minh)") && c.message().contains("giờ kết thúc lịch thi"));
        assertEquals(Set.of("examTitle","timezone","deadlineRule","startAt","endAt","offsetMinutes"),c.metadata().keySet());
        assertTrue(SchedulingMessages.opened(s,Instant.parse("2026-10-06T00:00:00Z")).message().contains("vào thi từ 08:00"));
        assertFalse(SchedulingMessages.opened(s,Instant.parse("2026-10-06T02:00:00Z")).message().contains("vào thi từ"));
        assertEquals("EXAM_UNSCHEDULED",SchedulingMessages.scheduleChanged("UNSCHEDULED",new SchedulingMessages.Schedule("Toán 12",null,null,"UTC","FULL_DURATION")).type());
    }
    ObjectNode schedule(){
        var p=json.createObjectNode();
        p.put("examId",UUID.randomUUID().toString()).put("examTitle","Toán").put("scheduleRevision",2).put("changeType","RESCHEDULED")
         .put("startAt","2026-10-06T01:00:00Z").put("endAt","2026-10-06T03:00:00Z").put("timezone","Asia/Ho_Chi_Minh").put("deadlineRule","FULL_DURATION")
         .put("examStatus","PUBLISHED").put("actorUserId",owner.toString());
        p.putArray("reminderOffsetsMinutes").add(15).add(1440);
        return p;
    }
    @Test void strictSchedulePayload(){
        var ok=SchedulingEvents.schedule(schedule());
        assertEquals(List.of(1440,15),ok.reminderOffsetsMinutes());
        var only=schedule();only.putNull("startAt");assertNull(SchedulingEvents.schedule(only).startAt());
        var none=schedule();none.putNull("actorUserId");none.putArray("reminderOffsetsMinutes");assertNull(SchedulingEvents.schedule(none).actorUserId());
        List<java.util.function.Consumer<ObjectNode>> broken=List.of(
            p->p.put("scheduleRevision",1.5),p->p.put("timezone","+07:00"),p->p.put("timezone","Mars/Base"),p->p.put("changeType","UNSCHEDULED"),
            p->{p.putNull("startAt");p.putNull("endAt");},p->p.remove("endAt"),p->p.remove("actorUserId"),p->p.put("endAt","2026-10-06T00:00:00Z"),
            p->p.put("startAt","2026-10-06 08:00"),p->p.putArray("reminderOffsetsMinutes").add(15).add(15),p->p.putArray("reminderOffsetsMinutes").add(0),
            p->p.putArray("reminderOffsetsMinutes").add(15.5),p->p.putArray("reminderOffsetsMinutes").add(43201),p->p.put("reminderOffsetsMinutes","15"),
            p->{var a=p.putArray("reminderOffsetsMinutes");for(int i=1;i<=11;i++)a.add(i);},p->p.put("deadlineRule","LATE"),p->p.put("examStatus","ARCHIVED"));
        for(int i=0;i<broken.size();i++){var p=schedule();broken.get(i).accept(p);int n=i;assertThrows(IllegalArgumentException.class,()->SchedulingEvents.schedule(p),"case "+n);}
    }
    @Test void strictLifecyclePayload(){
        var p=json.createObjectNode();p.put("examId",UUID.randomUUID().toString()).put("examTitle","T").put("status","OPEN").put("trigger","AUTO").putNull("actorUserId");p.put("lifecycleRevision",3);
        assertNull(SchedulingEvents.lifecycle(p).actorUserId());
        var manual=p.deepCopy();manual.put("trigger","MANUAL");assertThrows(IllegalArgumentException.class,()->SchedulingEvents.lifecycle(manual));
        var draft=p.deepCopy();draft.put("status","DRAFT");assertThrows(IllegalArgumentException.class,()->SchedulingEvents.lifecycle(draft));
        var frac=p.deepCopy();frac.put("lifecycleRevision",3.0);assertThrows(IllegalArgumentException.class,()->SchedulingEvents.lifecycle(frac));
    }
    @Test void strictRecipientsPayload(){
        var p=json.createObjectNode();p.put("examId",UUID.randomUUID().toString()).put("recipientRevision",4).put("cause","UNFOLLOWED").putNull("actorUserId");
        assertEquals(4,SchedulingEvents.recipients(p).recipientRevision());
        var joined=p.deepCopy();joined.put("cause","JOINED");assertEquals("JOINED",SchedulingEvents.recipients(joined).cause());
        for(var bad:List.<java.util.function.Consumer<ObjectNode>>of(x->x.put("recipientRevision",0),x->x.put("recipientRevision",4.5),x->x.put("cause","UNJOINED"),x->x.remove("actorUserId"),x->x.remove("examId"))){
            var c=p.deepCopy();bad.accept(c);assertThrows(IllegalArgumentException.class,()->SchedulingEvents.recipients(c));
        }
    }
    @Test void reminderKeyStablePerExamStartOffset(){
        UUID exam=UUID.randomUUID();Instant t=Instant.parse("2026-10-06T01:00:00Z");
        assertEquals(SchedulingConsumer.reminderKey(exam,t,15),SchedulingConsumer.reminderKey(exam,t,15));
        assertNotEquals(SchedulingConsumer.reminderKey(exam,t,15),SchedulingConsumer.reminderKey(exam,t.plusSeconds(60),15));
        assertEquals(Duration.ofSeconds(30),SchedulingJobs.backoff(1));assertEquals(Duration.ofSeconds(60),SchedulingJobs.backoff(2));assertEquals(Duration.ofMinutes(30),SchedulingJobs.backoff(20));
    }
}
