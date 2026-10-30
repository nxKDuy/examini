package com.datn.analytics.controller;

import com.datn.analytics.query.AnalyticsDto;
import com.datn.analytics.query.ExamAnalyticsQueries;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** System-wide activity for administrators only (systemPrivilege ADMIN), aggregates without student identities. */
@RestController
@RequestMapping("/analytics/admin")
@RequiredArgsConstructor
public class AdminAnalyticsController {
    private final ExamAnalyticsQueries queries;

    @GetMapping("/overview")
    @Operation(summary = "Hoạt động thi toàn hệ thống trong N ngày gần nhất")
    public AnalyticsDto.AdminOverview overview(@RequestParam(defaultValue = "30") int days, @RequestAttribute String systemPrivilege) {
        if (!"ADMIN".equals(systemPrivilege)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator privilege required");
        if (days < 1 || days > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "1 <= days <= 365");
        return queries.adminOverview(days);
    }
}
