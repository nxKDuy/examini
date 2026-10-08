package com.datn.analytics.security;

import com.datn.analytics.client.ExamClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Exam analytics expose every candidate's score, so they follow Grading's submission policy:
 * OWNER and MANAGER only (Exam "manage" permission). PROCTOR, unrelated teachers and ADMIN
 * without membership are denied by Exam itself; persona is never a global grant.
 */
@Component
@RequiredArgsConstructor
public class ExamAnalyticsAccess {
    private final ExamClient exams;
    @Value("${analytics.exam-permission-token:}") private String token = "";

    public String require(UUID examId, UUID actor) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Exam integration not configured");
        try {
            return exams.permission(examId, actor, true, token);
        } catch (FeignException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Exam analytics permission denied");
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam not found");
        } catch (FeignException e) {
            // Includes 401 while Exam does not yet accept the permission-only token: fail closed.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Exam authorization unavailable");
        }
    }
}
