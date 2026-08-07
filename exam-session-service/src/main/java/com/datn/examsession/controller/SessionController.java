package com.datn.examsession.controller;

import com.datn.examsession.entity.ProctoringLog;
import com.datn.examsession.service.ProctoringService;
import com.datn.examsession.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
@Tag(name = "Exam Session", description = "Quản lý phiên thi của thí sinh")
public class SessionController {

    private final SessionService sessionService;
    private final ProctoringService proctoringService;

    @PostMapping("/start")
    @Operation(summary = "Bắt đầu thi")
    public SessionService.StartSessionResponse startExam(
            @RequestParam String examCode,
            @RequestHeader("X-User-Id") UUID userId) {
        return sessionService.startExam(examCode, userId);
    }

    @PostMapping("/{sessionId}/answers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Lưu câu trả lời")
    public void saveAnswer(
            @PathVariable UUID sessionId,
            @RequestParam UUID questionId,
            @RequestBody AnswerRequest req,
            @RequestHeader("X-User-Id") UUID userId) {
        sessionService.saveAnswer(sessionId, userId, questionId, req.content());
    }

    @PostMapping("/{sessionId}/submit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Nộp bài thủ công")
    public void submitExam(
            @PathVariable UUID sessionId,
            @RequestHeader("X-User-Id") UUID userId) {
        sessionService.submitExam(sessionId, userId);
    }

    @PostMapping("/{sessionId}/proctoring")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ghi log giám thị ảo")
    public void logProctoringEvent(
            @PathVariable UUID sessionId,
            @RequestParam ProctoringLog.EventType eventType,
            @RequestParam(required = false) String description,
            @RequestHeader("X-User-Id") UUID userId) {
        proctoringService.logEvent(sessionId, userId, eventType, description);
    }

    @GetMapping("/{sessionId}/internal/answers")
    @Operation(summary = "API NỘI BỘ: Lấy chi tiết bài làm để chấm điểm")
    public SessionService.SessionDetailDto getSessionDetails(@PathVariable UUID sessionId) {
        return sessionService.getSessionDetails(sessionId);
    }

    public record AnswerRequest(String content) {}
}
