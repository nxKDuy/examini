package com.datn.exam;

import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.client.SessionClient;
import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.StartAdmission;
import com.datn.exam.repository.ExamRepository;
import com.datn.exam.repository.StartAdmissionRepository;
import com.datn.exam.service.ScheduleService;
import com.datn.exam.service.StartAdmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","exam.internal-token=test-internal","exam.schedule.sweep-ms=3600000","exam.start-admission-reconcile-ms=3600000"})
class StartAdmissionRecoveryTest {
    @Autowired StartAdmissionService admissions;
    @Autowired StartAdmissionRepository admissionRepository;
    @Autowired ExamRepository exams;
    @Autowired com.datn.exam.repository.ExamParticipantRepository participantRows;
    @Autowired ScheduleService schedules;
    @Autowired JdbcTemplate jdbc;
    @MockBean SessionClient sessions;
    @MockBean QuestionBankClient questions;
    UUID owner,user,examId;

    @BeforeEach void setup() {
        owner=UUID.randomUUID();user=UUID.randomUUID();
        Exam exam=Exam.builder().code(UUID.randomUUID().toString()).title("Admission").durationMinutes(30)
                .status(Exam.Status.OPEN).createdBy(owner).startAt(Instant.now().minusSeconds(60))
                .endAt(Instant.now().plusSeconds(7200)).scheduleRevision(3).build();
        examId=exams.saveAndFlush(exam).getId();participantRows.saveAndFlush(new com.datn.exam.entity.ExamParticipant(examId,user,"ASSIGNED",owner,Instant.now()));
        when(sessions.eligibility(eq(examId),eq(user),eq("test-internal"))).thenReturn(new SessionClient.Eligibility(0,false));
        when(sessions.started(eq(examId),eq("test-internal"))).thenReturn(false);
    }

    @Test void lostConfirmResponseIsRecoveredAndPermanentlyLocksScheduleRule() {
        var reservation=admissions.reserve(examId,user);
        admissions.claim(examId,reservation.admissionId(),user);
        UUID sessionId=UUID.randomUUID();
        expire(reservation.admissionId());
        when(sessions.admissionStatus(examId,reservation.admissionId(),"test-internal"))
                .thenReturn(new ScheduleDto.AdmissionStatus(examId,reservation.admissionId(),sessionId,true));
        admissions.reconcileExpired();
        StartAdmission recovered=admissionRepository.findById(reservation.admissionId()).orElseThrow();
        assertEquals(StartAdmission.State.STARTED,recovered.getState());assertEquals(sessionId,recovered.getSessionId());
        assertNotNull(exams.findById(examId).orElseThrow().getFirstStudentStartedAt());
        var current=exams.findById(examId).orElseThrow();
        var update=new ScheduleDto.Update(OffsetDateTime.ofInstant(current.getStartAt(),ZoneOffset.UTC),OffsetDateTime.ofInstant(current.getEndAt().plusSeconds(60),ZoneOffset.UTC),"UTC",Exam.DeadlineRule.CAPPED_AT_END,false,false,current.getReminderOffsetsMinutes(),3,"change rule");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->schedules.update(examId,owner,update)).getStatusCode().value());
    }

    @Test void failedAdmissionIsReleasedAndDoesNotLockSchedule() {
        var reservation=admissions.reserve(examId,user);expire(reservation.admissionId());
        when(sessions.admissionStatus(examId,reservation.admissionId(),"test-internal"))
                .thenReturn(new ScheduleDto.AdmissionStatus(examId,reservation.admissionId(),null,false));
        admissions.reconcileExpired();
        assertEquals(StartAdmission.State.FAILED,admissionRepository.findById(reservation.admissionId()).orElseThrow().getState());
        var current=exams.findById(examId).orElseThrow();
        var update=new ScheduleDto.Update(OffsetDateTime.ofInstant(current.getStartAt(),ZoneOffset.UTC),OffsetDateTime.ofInstant(current.getEndAt().plusSeconds(60),ZoneOffset.UTC),"UTC",current.getDeadlineRule(),false,false,current.getReminderOffsetsMinutes(),3,"extend");
        assertEquals(4,schedules.update(examId,owner,update).scheduleRevision());
    }

    private void expire(UUID admissionId) {
        jdbc.update("update exam_start_admissions set expires_at=now()-interval '1 second' where id=?",admissionId);
    }
}
