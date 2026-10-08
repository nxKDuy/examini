package com.datn.exam;

import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.client.SessionClient;
import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.dto.Snapshot;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.ExamOutboxRepository;
import com.datn.exam.repository.ExamRepository;
import com.datn.exam.service.ExamCore;
import com.datn.exam.service.ExamScheduleMaintenance;
import com.datn.exam.service.ScheduleService;
import com.datn.exam.service.StartAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.internal-token=test-internal","notification.internal-token=test-notification","exam.events.outbox-initial-delay-ms=3600000","exam.events.outbox-poll-ms=3600000","exam.schedule.sweep-ms=3600000","exam.start-admission-reconcile-ms=3600000"})
@AutoConfigureMockMvc
@Import(SchedulingAcceptanceTest.ClockConfig.class)
class SchedulingAcceptanceTest {
    @Autowired ExamRepository exams;
    @Autowired com.datn.exam.repository.ExamParticipantRepository participantRows;
    @Autowired ExamOutboxRepository outbox;
    @Autowired ExamCore core;
    @Autowired ScheduleService schedules;
    @Autowired StartAdmissionService admissions;
    @Autowired ExamScheduleMaintenance maintenance;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @MockBean SessionClient sessions;
    @MockBean QuestionBankClient questions;

    UUID owner,manager,proctor,student;
    Instant base;

    @BeforeEach void setup() {
        owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();student=UUID.randomUUID();
        base=Instant.parse("2030-03-10T10:00:00Z");clock.set(base);
        when(sessions.started(any(),eq("test-internal"))).thenReturn(false);
        when(sessions.eligibility(any(),any(),eq("test-internal"))).thenReturn(new SessionClient.Eligibility(0,false));
        when(sessions.availability(any(),any(),eq("test-internal"))).thenReturn(new SessionClient.Availability(0,false,null,0,null,null));
    }

    @Test void manualOpenStillWaitsForStartAndDeadlineRulesHonorExactBoundaries() {
        Exam early=exam(Exam.Status.PUBLISHED,base.plusSeconds(60),base.plusSeconds(7200),Exam.DeadlineRule.FULL_DURATION,false,false);
        core.transition(early.getId(),owner,Exam.Status.OPEN);
        assertEquals("BEFORE_START_AT",schedules.availability(early.getId(),student,null).reasonCode());
        assertStatus(409,()->core.join(early.getId(),student,null));
        assertStatus(409,()->admissions.reserve(early.getId(),student));

        clock.set(base.plusSeconds(60));
        var full=admissions.claim(early.getId(),admissions.reserve(early.getId(),student).admissionId(),student);
        assertEquals(clock.instant().plusSeconds(1800),full.deadlineAt());

        clock.set(base.plusSeconds(50*60));
        Exam capped=exam(Exam.Status.OPEN,base,base.plusSeconds(3600),Exam.DeadlineRule.CAPPED_AT_END,false,false);
        var cappedClaim=admissions.claim(capped.getId(),admissions.reserve(capped.getId(),student).admissionId(),student);
        assertEquals(capped.getEndAt(),cappedClaim.deadlineAt());

        clock.set(base.plusSeconds(30*60));
        Exam exact=exam(Exam.Status.OPEN,base,base.plusSeconds(3600),Exam.DeadlineRule.REQUIRE_FULL_WINDOW,false,false);
        var exactClaim=admissions.claim(exact.getId(),admissions.reserve(exact.getId(),student).admissionId(),student);
        assertEquals(exact.getEndAt(),exactClaim.deadlineAt());

        clock.set(base.plusSeconds(30*60+1));
        Exam late=exam(Exam.Status.OPEN,base,base.plusSeconds(3600),Exam.DeadlineRule.REQUIRE_FULL_WINDOW,false,false);
        var lateReservation=admissions.reserve(late.getId(),student);
        assertStatus(409,()->admissions.claim(late.getId(),lateReservation.admissionId(),student));

        clock.set(base.plusSeconds(3600));
        Exam ended=exam(Exam.Status.OPEN,base,base.plusSeconds(3600),Exam.DeadlineRule.FULL_DURATION,false,false);
        assertStatus(409,()->admissions.reserve(ended.getId(),student));
    }

    @Test void manualCloseNeverAutoReopensAndDowntimeCatchupDoesNotEmitFalseOpen() {
        Exam manuallyClosed=exam(Exam.Status.OPEN,base.minusSeconds(60),base.plusSeconds(3600),Exam.DeadlineRule.FULL_DURATION,true,false);
        core.transition(manuallyClosed.getId(),owner,Exam.Status.CLOSED,"maintenance","MANUAL");
        maintenance.reconcile();
        assertEquals(Exam.Status.CLOSED,exams.findById(manuallyClosed.getId()).orElseThrow().getStatus());

        Exam autoClosePublished=exam(Exam.Status.PUBLISHED,base.minusSeconds(7200),base.minusSeconds(1),Exam.DeadlineRule.FULL_DURATION,false,true);
        Exam missedWindow=exam(Exam.Status.PUBLISHED,base.minusSeconds(7200),base.minusSeconds(1),Exam.DeadlineRule.FULL_DURATION,true,false);
        Exam catchupClose=exam(Exam.Status.PUBLISHED,base.minusSeconds(7200),base.minusSeconds(1),Exam.DeadlineRule.FULL_DURATION,true,true);
        Exam dueToOpen=exam(Exam.Status.PUBLISHED,base,base.plusSeconds(3600),Exam.DeadlineRule.FULL_DURATION,true,false);
        maintenance.reconcile();
        assertEquals(Exam.Status.CLOSED,exams.findById(autoClosePublished.getId()).orElseThrow().getStatus());
        assertEquals(Exam.Status.PUBLISHED,exams.findById(missedWindow.getId()).orElseThrow().getStatus());
        assertEquals(Exam.Status.CLOSED,exams.findById(catchupClose.getId()).orElseThrow().getStatus());
        assertEquals(Exam.Status.OPEN,exams.findById(dueToOpen.getId()).orElseThrow().getStatus());
    }

    @Test void scheduleUpdateEnforcesRolesFirstStartAndDoesNotPublishFakeUnscheduledEvent() {
        Exam draft=exam(Exam.Status.DRAFT,null,null,Exam.DeadlineRule.FULL_DURATION,false,false);
        var update=new ScheduleDto.Update(null,null,"Asia/Ho_Chi_Minh",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(60),0,null);
        assertEquals(1,schedules.update(draft.getId(),manager,update).scheduleRevision());
        assertTrue(outbox.findAll().stream().noneMatch(event->event.getPayload().contains(draft.getId().toString())));

        var revisionOne=new ScheduleDto.Update(null,null,"UTC",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(60),1,null);
        assertStatus(403,()->schedules.update(draft.getId(),proctor,revisionOne));
        assertStatus(403,()->schedules.update(draft.getId(),UUID.randomUUID(),revisionOne));
        Exam locked=exams.findById(draft.getId()).orElseThrow();locked.setFirstStudentStartedAt(base);exams.saveAndFlush(locked);
        assertStatus(409,()->schedules.update(draft.getId(),manager,revisionOne));

        Exam scheduled=exam(Exam.Status.DRAFT,base.plusSeconds(60),base.plusSeconds(3600),Exam.DeadlineRule.FULL_DURATION,false,false);
        var scheduledUpdate=new ScheduleDto.Update(OffsetDateTime.ofInstant(scheduled.getStartAt(),ZoneOffset.UTC),OffsetDateTime.ofInstant(scheduled.getEndAt(),ZoneOffset.UTC),"UTC",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(120),0,null);
        schedules.update(scheduled.getId(),manager,scheduledUpdate);
        assertTrue(outbox.findAll().stream().anyMatch(event->event.getPayload().contains(scheduled.getId().toString()) && event.getPayload().contains("RESCHEDULED")));

        Exam partial=exam(Exam.Status.DRAFT,null,null,Exam.DeadlineRule.FULL_DURATION,false,false);
        var pastStartFutureEnd=new ScheduleDto.Update(OffsetDateTime.ofInstant(base.minusSeconds(3600),ZoneOffset.UTC),OffsetDateTime.ofInstant(base.plusSeconds(3600),ZoneOffset.UTC),"UTC",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(),0,null);
        assertEquals(base.minusSeconds(3600),schedules.update(partial.getId(),owner,pastStartFutureEnd).startAt());
        var ended=new ScheduleDto.Update(null,OffsetDateTime.ofInstant(base,ZoneOffset.UTC),"UTC",Exam.DeadlineRule.FULL_DURATION,false,false,List.of(),1,null);
        assertStatus(400,()->schedules.update(partial.getId(),owner,ended));
    }

    @Test void calendarIsAuthorityForMissedClosedVisibilityTimezoneAndPublicFollow() {
        Exam missed=exam(Exam.Status.PUBLISHED,base.minusSeconds(7200),base.minusSeconds(1),Exam.DeadlineRule.FULL_DURATION,false,false);
        missed.setTimezone("Asia/Ho_Chi_Minh");exams.saveAndFlush(missed);
        Exam earlyClosed=exam(Exam.Status.CLOSED,base.minusSeconds(60),base.plusSeconds(3600),Exam.DeadlineRule.FULL_DURATION,false,false);
        Exam endedAtRangeStart=exam(Exam.Status.PUBLISHED,base.minusSeconds(3600),base,Exam.DeadlineRule.FULL_DURATION,false,false);
        Exam publicExam=exam(Exam.Status.PUBLISHED,base.plusSeconds(3600),base.plusSeconds(7200),Exam.DeadlineRule.FULL_DURATION,false,false);
        publicExam.setAccessMode(Exam.AccessMode.PUBLIC);exams.saveAndFlush(publicExam);participantRows.deleteById(new com.datn.exam.entity.ExamParticipant.Key(publicExam.getId(),student));

        var initial=schedules.calendar(student,"STUDENT",base.minusSeconds(10000),base.plusSeconds(10000),true,PageRequest.of(0,100));
        var missedItem=initial.stream().filter(item->item.examId().equals(missed.getId())).findFirst().orElseThrow();
        assertEquals("MISSED",missedItem.calendarState());assertEquals("Asia/Ho_Chi_Minh",missedItem.timezone());
        assertEquals("CLOSED",initial.stream().filter(item->item.examId().equals(earlyClosed.getId())).findFirst().orElseThrow().calendarState());
        assertTrue(initial.stream().noneMatch(item->item.examId().equals(publicExam.getId())));
        assertTrue(schedules.calendar(student,"STUDENT",base,base.plusSeconds(10000),true,PageRequest.of(0,100)).stream().noneMatch(item->item.examId().equals(endedAtRangeStart.getId())));
        schedules.follow(publicExam.getId(),student,true);
        assertTrue(schedules.calendar(student,"STUDENT",base.minusSeconds(10000),base.plusSeconds(10000),true,PageRequest.of(0,100)).stream().anyMatch(item->item.examId().equals(publicExam.getId())));
        assertTrue(schedules.calendar(manager,"TEACHER",base.minusSeconds(10000),base.plusSeconds(10000),true,PageRequest.of(0,100)).stream().anyMatch(item->item.examId().equals(missed.getId())));
    }

    @Test void notificationProviderRejectsBearerAndWrongDedicatedToken() throws Exception {
        Exam draft=exam(Exam.Status.DRAFT,null,null,Exam.DeadlineRule.FULL_DURATION,false,false);
        String path="/exams/"+draft.getId()+"/internal/notification-recipients";
        mvc.perform(get(path).header("X-Service-Token","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer invalid").header("X-Service-Token","test-notification")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token","test-internal")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Service-Token","test-notification")).andExpect(status().isOk());
    }

    private Exam exam(Exam.Status status,Instant start,Instant end,Exam.DeadlineRule rule,boolean autoOpen,boolean autoClose) {
        UUID q=UUID.randomUUID(),option=UUID.randomUUID();
        Exam exam=Exam.builder().code(UUID.randomUUID().toString()).title("Scheduling").durationMinutes(30).status(status).createdBy(owner)
                .startAt(start).endAt(end).timezone("UTC").deadlineRule(rule).autoOpen(autoOpen).autoClose(autoClose)
                .snapshots(new ArrayList<>(List.of(new Snapshot(q,1,"MULTIPLE_CHOICE","Question",List.of(new Snapshot.Option(option,"A",0,true),new Snapshot.Option(UUID.randomUUID(),"B",1,false)),BigDecimal.ONE,null,0)))).build();
        exam.getMembers().put(manager,Exam.MemberRole.MANAGER);exam.getMembers().put(proctor,Exam.MemberRole.PROCTOR);
        exam=exams.saveAndFlush(exam);participantRows.saveAndFlush(new com.datn.exam.entity.ExamParticipant(exam.getId(),student,"ASSIGNED",owner,java.time.Instant.now()));
        return exam;
    }

    private static void assertStatus(int expected,Runnable operation) {
        assertEquals(expected,assertThrows(ResponseStatusException.class,operation::run).getStatusCode().value());
    }

    @TestConfiguration static class ClockConfig {
        @Bean @Primary MutableClock mutableClock(){return new MutableClock();}
    }
    static final class MutableClock extends Clock {
        private Instant instant=Instant.parse("2030-03-10T10:00:00Z");
        void set(Instant value){instant=value;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant;}
    }
}
