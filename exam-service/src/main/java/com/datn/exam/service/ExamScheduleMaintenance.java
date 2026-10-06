package com.datn.exam.service;

import com.datn.exam.entity.Exam;
import com.datn.exam.repository.ExamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.List;

@Component @RequiredArgsConstructor
public class ExamScheduleMaintenance {
    private final ExamRepository exams;
    private final ExamCore core;
    private final Clock clock;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ExamScheduleMaintenance.class);

    @Scheduled(fixedDelayString="${exam.schedule.sweep-ms:1000}")
    public void reconcile() {
        var now=clock.instant();
        for(Exam e:exams.findByStatusInAndAutoCloseTrue(List.of(Exam.Status.PUBLISHED,Exam.Status.OPEN))) {
            if(e.getEndAt()!=null && !now.isBefore(e.getEndAt()))transition(e.getId(),Exam.Status.CLOSED);
        }
        for(Exam e:exams.findByStatusInAndAutoOpenTrue(List.of(Exam.Status.PUBLISHED))) {
            if(e.getStartAt()!=null && !now.isBefore(e.getStartAt()) && (e.getEndAt()==null || now.isBefore(e.getEndAt())))transition(e.getId(),Exam.Status.OPEN);
        }
    }
    private void transition(java.util.UUID examId,Exam.Status status) {
        try {core.automaticTransition(examId,status);}
        catch(org.springframework.web.server.ResponseStatusException ignored) { }
        catch(RuntimeException failure){log.warn("Automatic Exam lifecycle update pending for examId={}",examId);}
    }
}
