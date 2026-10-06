package com.datn.exam.service;

import com.datn.exam.client.SessionClient;
import com.datn.exam.dto.ScheduleDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.StartAdmission;
import com.datn.exam.repository.StartAdmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class StartAdmissionService {
    private final ExamCore core;
    private final StartAdmissionRepository admissions;
    private final SessionClient sessions;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    @Value("${exam.internal-token:}") private String internalToken="";
    @Value("${exam.start-admission-lease-seconds:30}") private long leaseSeconds=30;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StartAdmissionService.class);

    @Transactional
    public ScheduleDto.Reservation reserve(UUID examId,UUID userId) {
        if(userId==null)throw ExamCore.error(401,"Authentication required");
        Exam exam=core.authorizeStart(examId,userId);
        if(admissions.existsByExamIdAndStateIn(examId,List.of(StartAdmission.State.PENDING,StartAdmission.State.CLAIMED)))
            throw ExamCore.error(409,"START_ADMISSION_IN_PROGRESS");
        Instant now=clock.instant();UUID id=UUID.randomUUID();
        admissions.saveAndFlush(new StartAdmission(id,examId,userId,exam.getScheduleRevision(),now,now.plusSeconds(leaseSeconds)));
        return new ScheduleDto.Reservation(examId,id,exam.getScheduleRevision());
    }

    @Transactional(noRollbackFor=org.springframework.web.server.ResponseStatusException.class)
    public ScheduleDto.Admission claim(UUID examId,UUID admissionId,UUID userId) {
        Exam exam=core.load(examId);StartAdmission admission=admissions.lockById(admissionId).orElseThrow(()->ExamCore.error(404,"Start admission not found"));
        if(!examId.equals(admission.getExamId()) || !userId.equals(admission.getUserId()))throw ExamCore.error(403,"Start admission identity mismatch");
        if(admission.getState()!=StartAdmission.State.PENDING || !clock.instant().isBefore(admission.getExpiresAt())) {
            if(admission.getState()==StartAdmission.State.PENDING)admission.failed();
            throw ExamCore.error(409,"START_ADMISSION_EXPIRED");
        }
        core.startWindow(exam);
        if(exam.getScheduleRevision()!=admission.getScheduleRevision())throw ExamCore.error(409,"SCHEDULE_REVISION_CHANGED");
        Instant startedAt=clock.instant();
        if(exam.getDeadlineRule()==Exam.DeadlineRule.REQUIRE_FULL_WINDOW &&
                Duration.between(startedAt,exam.getEndAt()).compareTo(Duration.ofMinutes(exam.getDurationMinutes()))<0)
            throw ExamCore.error(409,"INSUFFICIENT_TIME");
        Instant deadline=ScheduleRules.deadline(startedAt,exam.getDurationMinutes(),exam.getEndAt(),exam.getDeadlineRule());
        admission.claim(startedAt,deadline,startedAt.plusSeconds(leaseSeconds));
        return new ScheduleDto.Admission(admissionId,exam.getScheduleRevision(),startedAt,deadline);
    }

    @Transactional
    public void confirm(UUID examId,UUID admissionId,UUID sessionId) {
        if(sessionId==null)throw ExamCore.error(400,"Session id required");
        Exam exam=core.load(examId);StartAdmission admission=admissions.lockById(admissionId).orElseThrow(()->ExamCore.error(404,"Start admission not found"));
        if(!examId.equals(admission.getExamId()))throw ExamCore.error(404,"Start admission not found");
        if(admission.getState()==StartAdmission.State.STARTED) {
            if(!sessionId.equals(admission.getSessionId()))throw ExamCore.error(409,"Admission already bound to another session");
            return;
        }
        admission.started(sessionId);
        if(exam.getFirstStudentStartedAt()==null)exam.setFirstStudentStartedAt(admission.getStartedAt());
    }

    @Transactional
    public void release(UUID examId,UUID admissionId) {
        StartAdmission admission=admissions.lockById(admissionId).orElse(null);
        if(admission==null || !examId.equals(admission.getExamId()) || admission.getState()==StartAdmission.State.STARTED || admission.getState()==StartAdmission.State.FAILED)return;
        admission.failed();
    }

    @Scheduled(fixedDelayString="${exam.start-admission-reconcile-ms:5000}")
    public void reconcileExpired() {
        if(internalToken.isBlank())return;
        for(StartAdmission admission:admissions.findTop100ByStateInAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                List.of(StartAdmission.State.PENDING,StartAdmission.State.CLAIMED),clock.instant())) {
            try {
                var status=sessions.admissionStatus(admission.getExamId(),admission.getId(),internalToken);
                TransactionTemplate transaction=new TransactionTemplate(transactionManager);
                if(status.started() && status.sessionId()!=null)
                    transaction.executeWithoutResult(ignored->confirm(admission.getExamId(),admission.getId(),status.sessionId()));
                else transaction.executeWithoutResult(ignored->release(admission.getExamId(),admission.getId()));
            } catch(RuntimeException unavailable) {
                log.warn("Start admission reconciliation pending for admissionId={}",admission.getId());
            }
        }
    }
}
