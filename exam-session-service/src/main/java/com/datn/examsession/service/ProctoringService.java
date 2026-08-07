package com.datn.examsession.service;

import com.datn.examsession.entity.ExamSession;
import com.datn.examsession.entity.ProctoringLog;
import com.datn.examsession.repository.ProctoringLogRepository;
import com.datn.examsession.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProctoringService {

    private final ProctoringLogRepository logRepo;
    private final SessionRepository sessionRepo;

    @Transactional
    public void logEvent(UUID sessionId, UUID userId, ProctoringLog.EventType type, String description) {
        ExamSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session không tồn tại"));

        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền");
        }

        ProctoringLog log = ProctoringLog.builder()
                .session(session)
                .eventType(type)
                .description(description)
                .build();
        logRepo.save(log);
    }
}
