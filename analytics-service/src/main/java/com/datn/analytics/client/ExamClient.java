package com.datn.analytics.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Exam is the authority for exam existence and OWNER/MANAGER/PROCTOR membership. */
@FeignClient(name = "exam-service", url = "${exam-service.url:}", path = "/exams")
public interface ExamClient {
    /** Returns the actor's role; 403 when the actor lacks it, 404 when the exam does not exist. */
    @GetMapping("/{id}/internal/permission")
    String permission(@PathVariable UUID id, @RequestParam UUID actor, @RequestParam boolean manage,
                      @RequestHeader("X-Service-Token") String token);
}
